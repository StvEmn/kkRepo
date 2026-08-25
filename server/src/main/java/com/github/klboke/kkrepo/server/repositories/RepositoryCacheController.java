package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal admin endpoint to invalidate the per-repository caches of a proxy or group repository.
 *
 * <p>{@code POST /internal/repositories/{name}/cache/invalidate} clears the caches that keep a
 * proxy repository serving stale upstream state: the CONTENT and METADATA watermark tokens
 * ({@link NexusLikeCacheController#invalidateAll}), the 404 negative cache
 * ({@link ProxyNegativeCache#invalidateRepository}) and the asset metadata hot cache
 * ({@link AssetMetadataCache#evictRepository}). A group repository is invalidated by recursing
 * its member tree through {@link RepositoryDao#listMembers}: proxy members receive the full
 * three-piece invalidation, group members recurse, and a visited set guards against member
 * cycles. Hosted repositories own no upstream cache and are rejected; the caller needs the same
 * repository edit permission as the repositories admin API.
 *
 * <p>Multi-replica semantics: token bumps are persisted through the MySQL-backed watermark and
 * propagate to every replica within the local token TTL (default 2 seconds). Negative cache
 * entries embed the CONTENT token captured at write time and are dropped on token mismatch, so
 * stale 404s disappear on all replicas shortly after invalidation; the entry TTL remains as a
 * safety net. The asset metadata cache keeps its own version watermark, so invalidating on one
 * replica also discards stale hot data on siblings. The operation is idempotent and never
 * depends on the caller's transaction: every step acts immediately and repeats are safe.
 */
@RestController
@RequestMapping("/internal/repositories")
public class RepositoryCacheController {
  private static final List<String> PROXY_INVALIDATION_SUMMARY = List.of(
      "token:CONTENT", "token:METADATA", "negative-cache", "asset-metadata");
  private static final List<String> GROUP_INVALIDATION_SUMMARY = List.of(
      "token:CONTENT", "token:METADATA");

  private final RepositoryDao repositoryDao;
  private final NexusLikeCacheController cacheController;
  private final ProxyNegativeCache proxyNegativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  public RepositoryCacheController(
      RepositoryDao repositoryDao,
      NexusLikeCacheController cacheController,
      ProxyNegativeCache proxyNegativeCache,
      AssetMetadataCache assetMetadataCache,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.repositoryDao = repositoryDao;
    this.cacheController = cacheController;
    this.proxyNegativeCache = proxyNegativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  @PostMapping("/{name}/cache/invalidate")
  public Map<String, Object> invalidateCache(
      @PathVariable("name") String name, HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryRecord repository = repositoryDao.findByName(name)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Repository not found: " + name));
    requireEditPermission(subject, repository);
    if (repository.type() == RepositoryType.HOSTED || repository.id() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Cache invalidation is not supported for repository: " + name);
    }
    if (repository.type() == RepositoryType.PROXY) {
      invalidateProxy(repository.id());
      return Map.of(
          "repository", repository.name(),
          "type", RepositoryType.PROXY.name(),
          "invalidated", PROXY_INVALIDATION_SUMMARY);
    }
    Set<Long> visited = new LinkedHashSet<>();
    Set<String> membersInvalidated = new LinkedHashSet<>();
    invalidateGroup(repository, visited, membersInvalidated);
    return Map.of(
        "repository", repository.name(),
        "type", RepositoryType.GROUP.name(),
        "invalidated", GROUP_INVALIDATION_SUMMARY,
        "members", List.copyOf(membersInvalidated));
  }

  private void invalidateGroup(
      RepositoryRecord group, Set<Long> visited, Set<String> membersInvalidated) {
    if (group.id() == null || !visited.add(group.id())) {
      return;
    }
    cacheController.invalidateAll(group.id());
    for (RepositoryRecord member : repositoryDao.listMembers(group.id())) {
      if (member.id() == null) {
        continue;
      }
      membersInvalidated.add(member.name());
      if (member.type() == RepositoryType.PROXY) {
        invalidateProxy(member.id());
      } else if (member.type() == RepositoryType.GROUP) {
        invalidateGroup(member, visited, membersInvalidated);
      }
      // Hosted members carry no upstream caches; nothing to invalidate.
    }
  }

  private void invalidateProxy(long repositoryId) {
    cacheController.invalidateAll(repositoryId);
    proxyNegativeCache.invalidateRepository(repositoryId);
    assetMetadataCache.evictRepository(repositoryId);
  }

  private void requireEditPermission(AuthenticatedSubject subject, RepositoryRecord repository) {
    AccessDecision decision = securityService.decide(
        subject.permissionSubject(),
        new RepositoryPermission(repository.name(), repository.format(), "", PermissionAction.EDIT));
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  private AuthenticatedSubject requireAuthenticated(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());
    return authenticated.get();
  }

  private static Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return Optional.of(authenticated);
    }
    return Optional.empty();
  }
}
