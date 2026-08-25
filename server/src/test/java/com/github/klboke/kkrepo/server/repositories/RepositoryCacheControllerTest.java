package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RepositoryCacheControllerTest {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private static final String NEGATIVE_PATH = "org/example/lib/1.0/lib-1.0.pom";

  private InMemoryVersionWatermark watermark;
  private InMemorySharedCache sharedCache;
  private NexusLikeCacheController cacheController;
  private ProxyNegativeCache negativeCache;
  private AssetMetadataCache assetCache;

  @BeforeEach
  void setUp() {
    watermark = new InMemoryVersionWatermark();
    sharedCache = new InMemorySharedCache();
    cacheController = new NexusLikeCacheController(watermark, 60);
    negativeCache = new ProxyNegativeCache(sharedCache, true, 5, null, cacheController);
    assetCache = new AssetMetadataCache(sharedCache, true, 60, 5);
  }

  @Test
  void invalidateWithoutEditPermissionReturns403() throws Exception {
    RepositoryDao dao = daoWith(repository(1L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY));

    mockMvc(dao, AccessDecision.deny("missing edit permission"))
        .perform(post("/internal/repositories/maven-central/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isForbidden());

    assertEquals("0", cacheController.currentToken(1L, NexusCacheType.CONTENT));
    assertEquals("0", cacheController.currentToken(1L, NexusCacheType.METADATA));
  }

  @Test
  void invalidateUnknownRepositoryReturns404() throws Exception {
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.findByName("ghost")).thenReturn(Optional.empty());

    mockMvc(dao, AccessDecision.allow())
        .perform(post("/internal/repositories/ghost/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isNotFound());
  }

  @Test
  void invalidateHostedRepositoryIsRejectedWith400() throws Exception {
    RepositoryDao dao = daoWith(repository(3L, "maven-releases", RepositoryFormat.MAVEN2, RepositoryType.HOSTED));

    mockMvc(dao, AccessDecision.allow())
        .perform(post("/internal/repositories/maven-releases/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isBadRequest());

    assertEquals("0", cacheController.currentToken(3L, NexusCacheType.CONTENT));
  }

  @Test
  void invalidateProxyClearsNegativeCacheBumpsTokensAndEvictsAssetCache() throws Exception {
    RepositoryDao dao = daoWith(repository(1L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY));
    RepositoryRuntime runtime = proxyRuntime(1L, "maven-central");
    negativeCache.rememberNotFound(runtime, NEGATIVE_PATH);
    assertTrue(negativeCache.isNotFoundCached(runtime, NEGATIVE_PATH));
    AtomicInteger loads = new AtomicInteger();
    assetCache.find(1L, NEGATIVE_PATH, countingLoader(loads));
    assetCache.find(1L, NEGATIVE_PATH, countingLoader(loads));
    assertEquals(1, loads.get(), "second read must be served from the asset cache");
    String contentBefore = cacheController.currentToken(1L, NexusCacheType.CONTENT);
    String metadataBefore = cacheController.currentToken(1L, NexusCacheType.METADATA);

    MvcResult result = mockMvc(dao, AccessDecision.allow())
        .perform(post("/internal/repositories/maven-central/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> body = MAPPER.readValue(
        result.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {});
    assertEquals("maven-central", body.get("repository"));
    assertEquals("PROXY", body.get("type"));
    List<?> invalidated = (List<?>) body.get("invalidated");
    assertTrue(invalidated.containsAll(
        List.of("token:CONTENT", "token:METADATA", "negative-cache", "asset-metadata")),
        "summary must describe everything invalidated: " + invalidated);
    assertEquals(Long.parseLong(contentBefore) + 1,
        Long.parseLong(cacheController.currentToken(1L, NexusCacheType.CONTENT)),
        "CONTENT token must bump exactly once");
    assertEquals(Long.parseLong(metadataBefore) + 1,
        Long.parseLong(cacheController.currentToken(1L, NexusCacheType.METADATA)),
        "METADATA token must bump exactly once");
    assertFalse(negativeCache.isNotFoundCached(runtime, NEGATIVE_PATH),
        "negative entry must be discarded after the CONTENT token bump");
    assetCache.find(1L, NEGATIVE_PATH, countingLoader(loads));
    assertEquals(2, loads.get(), "asset metadata entry must be reloaded from source after eviction");
  }

  @Test
  void invalidateGroupRecursesThroughMemberTreeIncludingNestedGroups() throws Exception {
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.findByName("aggregator")).thenReturn(Optional.of(
        repository(10L, "aggregator", RepositoryFormat.MAVEN2, RepositoryType.GROUP)));
    when(dao.listMembers(10L)).thenReturn(List.of(
        repository(1L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY),
        repository(11L, "nested", RepositoryFormat.MAVEN2, RepositoryType.GROUP)));
    // The nested group carries a proxy member and a cycle back to the root group; the visited
    // set must stop the recursion from looping while still invalidating the whole tree.
    when(dao.listMembers(11L)).thenReturn(List.of(
        repository(2L, "s3-repo", RepositoryFormat.MAVEN2, RepositoryType.PROXY),
        repository(10L, "aggregator", RepositoryFormat.MAVEN2, RepositoryType.GROUP)));

    RepositoryRuntime central = proxyRuntime(1L, "maven-central");
    RepositoryRuntime s3 = proxyRuntime(2L, "s3-repo");
    negativeCache.rememberNotFound(central, NEGATIVE_PATH);
    negativeCache.rememberNotFound(s3, NEGATIVE_PATH);
    assertTrue(negativeCache.isNotFoundCached(central, NEGATIVE_PATH));
    assertTrue(negativeCache.isNotFoundCached(s3, NEGATIVE_PATH));
    String centralBefore = cacheController.currentToken(1L, NexusCacheType.CONTENT);
    String s3Before = cacheController.currentToken(2L, NexusCacheType.CONTENT);
    String nestedBefore = cacheController.currentToken(11L, NexusCacheType.CONTENT);
    String groupBefore = cacheController.currentToken(10L, NexusCacheType.CONTENT);

    MvcResult result = mockMvc(dao, AccessDecision.allow())
        .perform(post("/internal/repositories/aggregator/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> body = MAPPER.readValue(
        result.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {});
    assertEquals("aggregator", body.get("repository"));
    assertEquals("GROUP", body.get("type"));
    List<?> invalidated = (List<?>) body.get("invalidated");
    assertTrue(invalidated.containsAll(List.of("token:CONTENT", "token:METADATA")),
        "group summary must describe the token invalidation: " + invalidated);
    List<?> members = (List<?>) body.get("members");
    assertTrue(members.containsAll(List.of("maven-central", "s3-repo", "nested")),
        "summary must list every invalidated member: " + members);
    assertFalse(negativeCache.isNotFoundCached(central, NEGATIVE_PATH),
        "direct proxy member must be fully invalidated");
    assertFalse(negativeCache.isNotFoundCached(s3, NEGATIVE_PATH),
        "proxy member of the nested group must be fully invalidated");
    assertEquals(Long.parseLong(centralBefore) + 1,
        Long.parseLong(cacheController.currentToken(1L, NexusCacheType.CONTENT)));
    assertEquals(Long.parseLong(s3Before) + 1,
        Long.parseLong(cacheController.currentToken(2L, NexusCacheType.CONTENT)));
    assertEquals(Long.parseLong(nestedBefore) + 1,
        Long.parseLong(cacheController.currentToken(11L, NexusCacheType.CONTENT)),
        "nested group tokens must be invalidated by the recursion");
    assertEquals(Long.parseLong(groupBefore) + 1,
        Long.parseLong(cacheController.currentToken(10L, NexusCacheType.CONTENT)),
        "the group itself must be invalidated exactly once despite the cycle");
  }

  @Test
  void invalidateIsIdempotentAndSafeToRepeat() throws Exception {
    RepositoryDao dao = daoWith(repository(1L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY));
    RepositoryRuntime runtime = proxyRuntime(1L, "maven-central");
    negativeCache.rememberNotFound(runtime, NEGATIVE_PATH);
    MockMvc mockMvc = mockMvc(dao, AccessDecision.allow());

    mockMvc.perform(post("/internal/repositories/maven-central/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isOk());
    mockMvc.perform(post("/internal/repositories/maven-central/cache/invalidate")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject()))
        .andExpect(status().isOk());

    assertFalse(negativeCache.isNotFoundCached(runtime, NEGATIVE_PATH),
        "negative entry stays discarded after repeated calls");
    assertEquals("2", cacheController.currentToken(1L, NexusCacheType.CONTENT),
        "each call bumps the CONTENT token once, monotonically");
    assertEquals("2", cacheController.currentToken(1L, NexusCacheType.METADATA));
  }

  private MockMvc mockMvc(RepositoryDao dao, AccessDecision decision) {
    RepositoryCacheController controller = new RepositoryCacheController(
        dao,
        cacheController,
        negativeCache,
        assetCache,
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(decision));
    return MockMvcBuilders.standaloneSetup(controller).build();
  }

  private static RepositoryDao daoWith(RepositoryRecord record) {
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.findByName(record.name())).thenReturn(Optional.of(record));
    return dao;
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        format,
        type,
        format.name().toLowerCase(java.util.Locale.ROOT) + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
        true,
        1L,
        null,
        "https://upstream.example.com",
        null,
        null,
        null,
        true,
        Map.<String, Object>of());
  }

  private static RepositoryRuntime proxyRuntime(long id, String name) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        "ALLOW",
        "RELEASE",
        "STRICT",
        true,
        "https://upstream.example.com",
        1440,
        1440,
        List.of());
  }

  private static Supplier<Optional<AssetMetadataCache.Loaded>> countingLoader(AtomicInteger loads) {
    return () -> {
      loads.incrementAndGet();
      return Optional.of(new AssetMetadataCache.Loaded(assetRecord(), null));
    };
  }

  private static AssetRecord assetRecord() {
    return new AssetRecord(
        1L,
        1L,
        null,
        null,
        RepositoryFormat.MAVEN2,
        NEGATIVE_PATH,
        new byte[0],
        "lib-1.0.pom",
        "pom",
        "text/xml",
        1024L,
        null,
        Instant.parse("2026-06-01T00:00:00Z"),
        Map.of());
  }

  private static AuthenticatedSubject adminSubject() {
    return new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final Optional<AuthenticatedSubject> subject;

    private StubAuthenticationService(Optional<AuthenticatedSubject> subject) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(jakarta.servlet.http.HttpServletRequest request) {
      return subject;
    }
  }

  private static class StubSecurityService extends SecurityManagementService {
    private final AccessDecision decision;

    private StubSecurityService(AccessDecision decision) {
      super(new SecurityDaoAdapter(null, null));
      this.decision = decision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
      return decision;
    }
  }
}
