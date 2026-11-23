package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcCacheVersionDao;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;

/**
 * Real lock waits, a two-connection pool, and a proxied bump against both database engines —
 * run by {@code DatabaseServerSmokeTest} (requires Docker/Testcontainers).
 *
 * <p>Aligned with the post-convoy semantics: watermark bumps execute after the data commit (from
 * a {@code Pending.afterCommit} callback via a {@code REQUIRES_NEW} transaction manager), a failed
 * bump after commit is swallowed without retrying or rolling back the durable write, and the next
 * write's bump recovers. The strict low-frequency callers (authz/catalog) keep their
 * in-transaction bump and roll back the write when the bump fails.
 */
public final class CacheInvalidationSmokeChecks {
  private static final long REPOSITORY = 9900414L;
  private static final String VERSION = "asset-metadata:repo:" + REPOSITORY;

  private CacheInvalidationSmokeChecks() {}

  /** Persistence assembly provided by the runtime smoke harness. */
  public record Persistence(CacheVersionDao versions, PlatformTransactionManager transactions,
      Consumer<String> execute) {}

  /** Proxied bump entry point — see {@link #verify(HikariDataSource, Function, Function, boolean)}. */
  public interface BumpService {
    long bump(String name);
  }

  /**
   * Wraps a {@link JdbcCacheVersionDao} in a real Spring transaction proxy so the dao's
   * {@code @Transactional(REQUIRES_NEW)} on {@code bump()} is honored: the bump suspends the
   * caller's transaction, commits independently, and needs its own pooled connection.
   */
  public static BumpService proxiedBump(DataSource source, DatabaseDialect dialect) {
    JdbcCacheVersionDao dao = new JdbcCacheVersionDao(new JdbcTemplate(source), dialect);
    PlatformTransactionManager transactions = new DataSourceTransactionManager(source);
    TransactionInterceptor interceptor = new TransactionInterceptor(
        transactions, new AnnotationTransactionAttributeSource());
    ProxyFactory proxy = new ProxyFactory();
    proxy.setTarget(dao);
    proxy.setInterfaces(BumpService.class);
    proxy.addAdvice(interceptor);
    return (BumpService) proxy.getProxy();
  }

  /**
   * Verifies the post-commit bump semantics on the given database.
   *
   * @param source            the runtime application's pooled datasource (credentials and URL)
   * @param persistenceFactory builds DAOs/transaction accessors on top of a fresh test pool
   * @param bumpFactory       builds a <b>transaction-proxied</b> {@code bump(name)} entry point
   *                          whose {@code @Transactional(REQUIRES_NEW)} annotation is actually
   *                          applied (Spring proxy or equivalent template); a bare DAO object
   *                          would silently join the caller's transaction and void the test
   * @param mysql             selects MySQL vs PostgreSQL lock-timeout session SQL
   */
  public static void verify(HikariDataSource source,
      Function<DataSource, Persistence> persistenceFactory,
      Function<DataSource, BumpService> bumpFactory,
      boolean mysql) throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(source.getJdbcUrl());
    config.setUsername(source.getUsername());
    config.setPassword(source.getPassword());
    // The deferred bump runs in a REQUIRES_NEW transaction after the business connection has
    // committed but before it is returned to the pool, so the pool must offer a second
    // connection while the first is still checked out.
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1500);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    // A commit callback must not rely on setAutoCommit(true) accidentally committing its SQL.
    config.setAutoCommit(false);
    try (HikariDataSource pool = new HikariDataSource(config);
         Connection observer = DriverManager.getConnection(
             source.getJdbcUrl(), source.getUsername(), source.getPassword())) {
      try (var statement = observer.createStatement()) {
        statement.execute("CREATE TABLE cache_invalidation_probe (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
        statement.executeUpdate("INSERT INTO cache_invalidation_probe VALUES (1, 0)");
        statement.executeUpdate("INSERT INTO cache_version (name, version, updated_at) VALUES ('" + VERSION + "', 0, CURRENT_TIMESTAMP)");
      }
      try {
        Persistence persistence = persistenceFactory.apply(pool);
        BumpService bumps = bumpFactory.apply(pool);
        verify(pool, observer, persistence, bumps, mysql);
      } finally {
        if (!observer.getAutoCommit()) observer.rollback();
        observer.setAutoCommit(true);
        try (var statement = observer.createStatement()) {
          statement.execute("DROP TABLE cache_invalidation_probe");
          statement.executeUpdate("DELETE FROM cache_version WHERE name = '" + VERSION + "'");
        }
      }
    }
  }

  private static void verify(HikariDataSource pool, Connection observer,
      Persistence persistence, BumpService bumps, boolean mysql) throws Exception {
    // The watermark under test mirrors JdbcVersionWatermark but routes bump() through the
    // transaction-proxied service so REQUIRES_NEW genuinely suspends and opens a connection.
    JdbcVersionWatermark watermark = new JdbcVersionWatermark(new BumpDaoAdapter(bumps), 60);
    var cache = cache(watermark);
    var retry = new com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry(
        persistence.transactions(), 3, 0);

    // 1. The bump executes only after the data commit, and is durable once the caller returns.
    var dataCommitted = new CountDownLatch(1);
    retry.execute("atomic cache generation", () -> {
      persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
      cache.evictAfterCommit(REPOSITORY, "simple/a/");
      cache.evictAfterCommit(REPOSITORY, "simple/b/");
      cache.evictAfterCommit(REPOSITORY, "simple/a/"); // deduplicated to one bump
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
          try {
            assertEquals(1, read(observer, "SELECT value FROM cache_invalidation_probe"),
                "the data write must already be committed when the bump runs");
            assertEquals(0, read(observer, "SELECT version FROM cache_version WHERE name = '" + VERSION + "'"),
                "the bump must not execute before the data commit");
          } catch (AssertionError failure) {
            throw new IllegalStateException(failure);
          }
        }
      });
      dataCommitted.countDown();
      return null;
    });
    assertTrue(dataCommitted.await(10, TimeUnit.SECONDS));
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(),
        "every connection must be returned once the call completes");
    assertEquals(1, watermark.currentDurable(VERSION),
        "the post-commit bump committed and released its own connection");

    // 2. A bump that times out after the commit is swallowed: the write stays durable, the
    //    failure is neither retried nor propagated, and the connection is still returned.
    setLockTimeout(persistence, mysql);
    observer.setAutoCommit(false);
    try (var statement = observer.createStatement()) {
      statement.executeUpdate("UPDATE cache_version SET version = version + 1 WHERE name = '" + VERSION + "'");
    }
    var swallowed = new AtomicInteger();
    VersionWatermark contended = new VersionWatermark() {
      @Override public long bump(String name) {
        try {
          return watermark.bump(name);
        } catch (DataAccessException failure) {
          swallowed.incrementAndGet();
          throw failure;
        }
      }
      @Override public long current(String name) { return watermark.current(name); }
      @Override public long currentDurable(String name) { return watermark.currentDurable(name); }
      @Override public Map<String, Long> currentAll() { return watermark.currentAll(); }
    };
    try {
      retry.execute("swallow invalidation lock timeout after commit", () -> {
        persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
        cache(contended).evictAfterCommit(REPOSITORY, "simple/retry/");
        return null;
      });
    } finally {
      observer.rollback();
      observer.setAutoCommit(true);
    }
    assertEquals(1, swallowed.get(), "a bump failure after commit is swallowed, not retried");
    assertEquals(2, read(observer, "SELECT value FROM cache_invalidation_probe"),
        "the write stays committed");
    assertEquals(1, read(observer, "SELECT version FROM cache_version WHERE name = '" + VERSION + "'"),
        "only the blocking observer's bump landed; the failed one is lost to TTL");
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());

    // 3. The next write's bump recovers: the hot row is free again and advances from the
    //    observer's committed generation.
    retry.execute("recovered invalidation", () -> {
      persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
      cache.evictAfterCommit(REPOSITORY, "simple/next/");
      return null;
    });
    assertEquals(3, read(observer, "SELECT value FROM cache_invalidation_probe"));
    assertEquals(2, watermark.currentDurable(VERSION), "the next bump recovered after the failure");

    // 4. Strict low-frequency semantics: an in-transaction bump that times out propagates and
    //    rolls the business write back (authz/catalog style).
    observer.setAutoCommit(false);
    try (var statement = observer.createStatement()) {
      statement.executeUpdate("UPDATE cache_version SET version = version + 1 WHERE name = '" + VERSION + "'");
    }
    var strict = new TransactionTemplate(persistence.transactions());
    try {
      assertThrows(DataAccessException.class, () -> strict.execute(new TransactionCallbackWithoutResult() {
        @Override protected void doInTransactionWithoutResult(TransactionStatus status) {
          setLockTimeout(persistence, mysql);
          persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
          bumps.bump(VERSION);
        }
      }));
    } finally {
      observer.rollback();
      observer.setAutoCommit(true);
    }
    assertEquals(3, read(observer, "SELECT value FROM cache_invalidation_probe"),
        "the strict write rolled back with its failed bump");
    assertEquals(2, watermark.currentDurable(VERSION));
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());

    com.github.klboke.kkrepo.server.catalog.CatalogInvalidationSmokeChecks.verify(
        persistence, pool, observer);
  }

  /** Routes JdbcVersionWatermark's bumps through the transaction-proxied service. */
  private static final class BumpDaoAdapter implements CacheVersionDao {
    private final BumpService bumps;

    BumpDaoAdapter(BumpService bumps) {
      this.bumps = bumps;
    }

    @Override public long bump(String name) {
      return bumps.bump(name);
    }

    @Override public long current(String name) {
      throw new UnsupportedOperationException("use watermark.currentDurable in this smoke");
    }

    @Override public Map<String, Long> selectAll() {
      throw new UnsupportedOperationException();
    }
  }

  private static AssetMetadataCache cache(VersionWatermark watermark) {
    return new AssetMetadataCache(new com.github.klboke.kkrepo.cache.InMemorySharedCache(
        new com.fasterxml.jackson.databind.ObjectMapper(), 100, null), watermark, true, 60, 5);
  }

  private static void setLockTimeout(Persistence persistence, boolean mysql) {
    persistence.execute().accept(mysql
        ? "SET SESSION innodb_lock_wait_timeout = 1" : "SET LOCAL lock_timeout = '200ms'");
  }

  private static long read(Connection connection, String sql) {
    try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
  }
}
