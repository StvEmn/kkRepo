package com.github.klboke.kkrepo.server.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction-scoped invalidations, not a cache or an asynchronous work queue.
 *
 * <p>Cascading group changes join the asset transaction and run immediately before commit.
 * Repository watermarks, however, are bumped best-effort after commit: the bump's hot-row lock
 * must never extend the business transaction's lock window, and a failed bump no longer rolls
 * back an already committed write — replicas may briefly serve stale cache until TTL expiry or
 * the next write re-invalidates. Only local cache eviction and watermark bumps run after commit;
 * no additional pooled connection is needed before commit.
 */
public final class CacheInvalidation {
  private static final Logger log = LoggerFactory.getLogger(CacheInvalidation.class);
  private static final Object RESOURCE = CacheInvalidation.class.getName();

  private CacheInvalidation() {}

  public static void beforeCommit(Object key, Runnable mutation) {
    Pending pending = pending();
    if (pending == null) {
      mutation.run();
    } else {
      pending.requireBeforeCommit();
      pending.mutations.putIfAbsent(key, mutation);
    }
  }

  public static void watermarkBeforeCommit(String name, Runnable bump) {
    Pending pending = pending();
    if (pending == null) {
      bump.run();
    } else {
      // Registered before commit only to reject late registrations; execution itself is
      // deferred to afterCommit so the hot-row lock never overlaps the business transaction.
      pending.requireBeforeCommit();
      pending.watermarks.putIfAbsent(name, bump);
    }
  }

  public static void afterCommit(Object key, Runnable eviction) {
    Pending pending = pending();
    if (pending == null) {
      eviction.run();
    } else {
      pending.evictions.putIfAbsent(key, eviction);
    }
  }

  // afterCommit note: watermark/eviction runnables that throw an Error (not a RuntimeException)
  // abort the remaining afterCommit queue and propagate to the commit() caller — RuntimeExceptions
  // are swallowed per-callback, Errors are not. Current trade-off: an Error after commit means the
  // JVM is likely broken anyway; the write is already durable either way.

  private static Pending pending() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
    Pending pending = (Pending) TransactionSynchronizationManager.getResource(RESOURCE);
    if (pending == null) {
      pending = new Pending();
      TransactionSynchronizationManager.bindResource(RESOURCE, pending);
      TransactionSynchronizationManager.registerSynchronization(pending);
    }
    return pending;
  }

  private static final class Pending implements TransactionSynchronization {
    private final Map<Object, Runnable> mutations = new LinkedHashMap<>();
    private final Map<String, Runnable> watermarks = new TreeMap<>();
    private final Map<Object, Runnable> evictions = new LinkedHashMap<>();
    private boolean committed;

    private void requireBeforeCommit() {
      if (committed) {
        throw new IllegalStateException("Durable cache invalidation must be scheduled before commit");
      }
    }

    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResource(RESOURCE);
    }

    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(RESOURCE, this);
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      // Cascades can enqueue more group mutations and watermarks. Drain mutations in this same
      // callback rather than registering a synchronization too late for Spring's snapshot.
      // Watermarks stay queued; they execute in afterCommit so their hot-row lock never
      // extends the business transaction and convoys every concurrent writer.
      drain(mutations);
    }

    @Override
    public void afterCommit() {
      committed = true;
      // Watermark bumps run after the business write is durable: a failure here must never
      // propagate (an exception would make TransientTransactionRetry replay an already
      // committed write). Log it — replicas may serve stale cache until TTL or the next write.
      while (!watermarks.isEmpty()) {
        try {
          takeFirst(watermarks).run();
        } catch (RuntimeException e) {
          log.warn("Write already committed, but cache version bump failed; replicas may serve "
              + "stale cache until TTL or the next write", e);
        }
      }
      while (!evictions.isEmpty()) {
        try {
          takeFirst(evictions).run();
        } catch (RuntimeException e) {
          // The database has committed. A local-cache failure must never replay that write.
          log.warn("Failed evicting local cache after commit; TTL remains the fallback", e);
        }
      }
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
    }

    private static void drain(Map<?, Runnable> actions) {
      while (!actions.isEmpty()) takeFirst(actions).run();
    }

    private static Runnable takeFirst(Map<?, Runnable> actions) {
      var iterator = actions.values().iterator();
      Runnable action = iterator.next();
      iterator.remove();
      return action;
    }
  }
}
