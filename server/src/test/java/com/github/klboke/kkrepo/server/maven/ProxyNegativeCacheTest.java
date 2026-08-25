package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProxyNegativeCacheTest {
  private static final String NAMESPACE = "proxy-negative-cache";

  @Test
  void cachedEntryHitsWhileContentTokenUnchanged() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    ProxyNegativeCache cache = new ProxyNegativeCache(shared, true, 5, null, controller);
    RepositoryRuntime runtime = proxyRuntime(101L, "central-proxy");
    String path = "org/example/missing/1.0.0/missing-1.0.0.pom";

    cache.rememberNotFound(runtime, path);

    assertTrue(cache.isNotFoundCached(runtime, path));
  }

  @Test
  void cachedEntryMissesAndIsEvictedAfterContentTokenInvalidation() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    ProxyNegativeCache cache = new ProxyNegativeCache(shared, true, 5, null, controller);
    RepositoryRuntime runtime = proxyRuntime(101L, "central-proxy");
    String path = "org/example/missing/1.0.0/missing-1.0.0.pom";

    cache.rememberNotFound(runtime, path);
    assertTrue(cache.isNotFoundCached(runtime, path));

    controller.invalidate(runtime.id(), NexusCacheType.CONTENT);

    assertFalse(cache.isNotFoundCached(runtime, path));
    assertTrue(shared.getString(NAMESPACE, key(runtime, path)).isEmpty());
  }

  @Test
  void threeArgConstructorDegradesToPlainTtlCache() {
    InMemorySharedCache shared = new InMemorySharedCache();
    ProxyNegativeCache cache = new ProxyNegativeCache(shared, true, 5);
    RepositoryRuntime runtime = proxyRuntime(101L, "central-proxy");
    String path = "org/example/missing/1.0.0/missing-1.0.0.pom";

    cache.rememberNotFound(runtime, path);

    assertTrue(cache.isNotFoundCached(runtime, path));
    assertEquals("404", shared.getString(NAMESPACE, key(runtime, path)).orElseThrow());
  }

  @Test
  void threeArgConstructorMissesOnceEntryHasExpired() {
    ProxyNegativeCache cache = new ProxyNegativeCache(new ExpiredOnReadCache(), true, 1);
    RepositoryRuntime runtime = proxyRuntime(101L, "central-proxy");
    String path = "org/example/missing/1.0.0/missing-1.0.0.pom";

    cache.rememberNotFound(runtime, path);

    assertFalse(cache.isNotFoundCached(runtime, path));
  }

  @Test
  void disabledCacheNeitherStoresNorHits() {
    InMemorySharedCache shared = new InMemorySharedCache();
    ProxyNegativeCache cache = new ProxyNegativeCache(shared, false, 5);
    RepositoryRuntime runtime = proxyRuntime(101L, "central-proxy");
    String path = "org/example/missing/1.0.0/missing-1.0.0.pom";

    cache.rememberNotFound(runtime, path);

    assertFalse(cache.isNotFoundCached(runtime, path));
    assertTrue(shared.getString(NAMESPACE, key(runtime, path)).isEmpty());
  }

  @Test
  void nonProxyRepositoryIsNotCached() {
    InMemorySharedCache shared = new InMemorySharedCache();
    ProxyNegativeCache cache = new ProxyNegativeCache(shared, true, 5);
    RepositoryRuntime hosted = new RepositoryRuntime(
        202L, "local-hosted", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted",
        true, 2L, "ALLOW", null, null, true, null, null, null, List.of());
    String path = "org/example/anything/1.0.0/anything-1.0.0.pom";

    cache.rememberNotFound(hosted, path);

    assertFalse(cache.isNotFoundCached(hosted, path));
    assertTrue(shared.getString(NAMESPACE, key(hosted, path)).isEmpty());
  }

  private static RepositoryRuntime proxyRuntime(long id, String name) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.MAVEN2, RepositoryType.PROXY, "maven2-proxy",
        true, id, "ALLOW", null, null, true, "https://example.invalid", 1440, 1440, List.of());
  }

  private static String key(RepositoryRuntime runtime, String path) {
    return runtime.id() + ":" + path;
  }

  /**
   * Shared cache whose entries are treated as expired on read, mirroring what the in-memory cache
   * does once the entry TTL passes (the read path returns empty and clears the entry).
   */
  private static class ExpiredOnReadCache extends InMemorySharedCache {
    @Override
    public Optional<String> getString(String namespace, String key) {
      return Optional.empty();
    }
  }
}
