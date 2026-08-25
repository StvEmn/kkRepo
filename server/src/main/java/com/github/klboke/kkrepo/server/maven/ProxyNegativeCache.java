package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Shared proxy-path 404 cache, matching Nexus' NegativeCacheHandler shape.
 *
 * <p>The cache is scoped to a concrete proxy repository and path. Group repositories still probe
 * members in order; only a proxy member that has already seen a 404 for the same path can
 * short-circuit the remote call.
 *
 * <p>Multi-replica semantics: when a {@link NexusLikeCacheController} is injected, entries store
 * the repository CONTENT cache token (a {@link NexusLikeCacheInfo}) captured at write time. Reads
 * validate the entry against the current token via
 * {@link NexusLikeCacheController#isStale(long, NexusCacheType, NexusLikeCacheInfo, int, Instant)};
 * a token mismatch (e.g. after an upstream write or an explicit cache refresh) evicts the entry and
 * treats the path as not cached. Token bumps propagate across replicas through the MySQL-backed
 * watermark within the local token TTL (default 2 seconds), so stale 404s disappear on every
 * replica shortly after invalidation; the entry TTL remains as a safety net. When no token
 * controller is injected (legacy three- and four-argument constructors), the cache degrades to
 * plain TTL semantics and stores the plain "404" marker.
 */
@Service
public class ProxyNegativeCache {
  private static final Logger log = LoggerFactory.getLogger(ProxyNegativeCache.class);
  private static final String NAMESPACE = "proxy-negative-cache";
  private static final String NOT_FOUND = "404";
  /** Token-only staleness check: no max-age, the entry TTL remains the safety net. */
  private static final int NO_MAX_AGE_MINUTES = -1;

  private final SharedCache cache;
  private final NexusLikeCacheController cacheController;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final Duration ttl;

  public ProxyNegativeCache(
      SharedCache cache,
      @Value("${kkrepo.proxy.negative-cache.enabled:${kkrepo.maven.proxy-negative-cache.enabled:true}}")
      boolean enabled,
      @Value("${kkrepo.proxy.negative-cache.ttl-minutes:${kkrepo.maven.proxy-negative-cache.ttl-minutes:5}}")
      long ttlMinutes) {
    this(cache, enabled, ttlMinutes, null, null);
  }

  public ProxyNegativeCache(
      SharedCache cache,
      @Value("${kkrepo.proxy.negative-cache.enabled:${kkrepo.maven.proxy-negative-cache.enabled:true}}")
      boolean enabled,
      @Value("${kkrepo.proxy.negative-cache.ttl-minutes:${kkrepo.maven.proxy-negative-cache.ttl-minutes:5}}")
      long ttlMinutes,
      KkRepoMetrics metrics) {
    this(cache, enabled, ttlMinutes, metrics, null);
  }

  @Autowired
  public ProxyNegativeCache(
      SharedCache cache,
      @Value("${kkrepo.proxy.negative-cache.enabled:${kkrepo.maven.proxy-negative-cache.enabled:true}}")
      boolean enabled,
      @Value("${kkrepo.proxy.negative-cache.ttl-minutes:${kkrepo.maven.proxy-negative-cache.ttl-minutes:5}}")
      long ttlMinutes,
      KkRepoMetrics metrics,
      NexusLikeCacheController cacheController) {
    this.cache = cache;
    this.cacheController = cacheController;
    this.metrics = metrics;
    this.enabled = enabled;
    this.ttl = Duration.ofMinutes(Math.max(0, ttlMinutes));
  }

  public boolean isNotFoundCached(RepositoryRuntime runtime, MavenPath path) {
    return isNotFoundCached(runtime, path.path());
  }

  public boolean isNotFoundCached(RepositoryRuntime runtime, String path) {
    if (!enabled || ttl.isZero() || !runtime.isProxy()) {
      return false;
    }
    boolean cached = isCached(runtime, key(runtime, path));
    record(runtime, cached ? "negative_hit" : "negative_miss");
    return cached;
  }

  private boolean isCached(RepositoryRuntime runtime, String cacheKey) {
    if (cacheController == null) {
      return cache.getString(NAMESPACE, cacheKey).map(NOT_FOUND::equals).orElse(false);
    }
    try {
      Optional<NexusLikeCacheInfo> entry =
          cache.getJson(NAMESPACE, cacheKey, NexusLikeCacheInfo.class);
      if (entry.isEmpty()) {
        return false;
      }
      if (cacheController.isStale(
          runtime.id(), NexusCacheType.CONTENT, entry.get(), NO_MAX_AGE_MINUTES, Instant.now())) {
        cache.evict(NAMESPACE, cacheKey);
        return false;
      }
      return true;
    } catch (RuntimeException e) {
      log.warn("Failed reading negative cache for repository {} path {}", runtime.id(), cacheKey, e);
      return false;
    }
  }

  public void rememberNotFound(RepositoryRuntime runtime, MavenPath path) {
    rememberNotFound(runtime, path.path());
  }

  public void rememberNotFound(RepositoryRuntime runtime, String path) {
    if (!enabled || ttl.isZero() || !runtime.isProxy()) {
      return;
    }
    String cacheKey = key(runtime, path);
    if (cacheController == null) {
      cache.putString(NAMESPACE, cacheKey, NOT_FOUND, ttl);
    } else {
      try {
        cache.putJson(
            NAMESPACE,
            cacheKey,
            cacheController.current(runtime.id(), NexusCacheType.CONTENT, Instant.now()),
            ttl);
      } catch (RuntimeException e) {
        log.warn("Failed writing negative cache for repository {} path {}", runtime.id(), cacheKey, e);
        return;
      }
    }
    record(runtime, "negative_store");
  }

  public void invalidate(RepositoryRuntime runtime, MavenPath path) {
    invalidate(runtime, path.path());
  }

  public void invalidate(RepositoryRuntime runtime, String path) {
    if (!runtime.isProxy()) {
      return;
    }
    cache.evict(NAMESPACE, key(runtime, path));
    record(runtime, "negative_invalidate");
  }

  public void invalidateRepository(long repositoryId) {
    cache.evictByPrefix(NAMESPACE, repositoryId + ":");
  }

  private static String key(RepositoryRuntime runtime, String path) {
    return runtime.id() + ":" + path;
  }

  private void record(RepositoryRuntime runtime, String result) {
    if (metrics != null) {
      metrics.recordProxyCacheEvent(runtime, result);
    }
  }
}
