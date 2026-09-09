package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapStatus;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ContentSelectorCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ContentSelectorView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.LdapSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.LdapSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.OidcSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.OidcSettingsView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RepositoryTargetView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.SecuritySummary;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.SessionView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/security")
public class SecurityManagementController {
  private final SecurityManagementService securityService;

  public SecurityManagementController(SecurityManagementService securityService) {
    this.securityService = securityService;
  }

  @GetMapping("/bootstrap")
  public AdminBootstrapStatus bootstrap() {
    return securityService.adminBootstrapStatus();
  }

  @PostMapping("/bootstrap/admin")
  public UserView initializeAdmin(@RequestBody(required = false) AdminBootstrapCommand command) {
    return securityService.initializeAdmin(command);
  }

  @GetMapping("/summary")
  public SecuritySummary summary() {
    return securityService.summary();
  }

  @GetMapping("/session")
  public SessionView session(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request);
    List<String> roles = subject.permissionSubject() == null || subject.permissionSubject().groupIds() == null
        ? List.of()
        : subject.permissionSubject().groupIds().stream().sorted().toList();
    return new SessionView(
        subject.source(),
        subject.userId(),
        subject.realmId(),
        subject.apiKeyId(),
        roles);
  }

  @GetMapping("/permissions")
  public List<String> permissions(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.listEffectivePermissions(subject.permissionSubject());
  }

  @GetMapping("/users")
  public List<UserView> users(
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "userId", required = false) String userId) {
    return securityService.listUsers(source, userId);
  }

  @PostMapping("/users")
  public UserView saveUser(@RequestBody UserCommand command) {
    return securityService.saveUser(command);
  }

  @PutMapping("/users/{source}/{userId}")
  public UserView updateUser(
      @PathVariable("source") String source,
      @PathVariable("userId") String userId,
      @RequestBody UserCommand command) {
    return securityService.saveUser(new UserCommand(
        source,
        userId,
        command.firstName(),
        command.lastName(),
        command.email(),
        command.password(),
        command.passwordHash(),
        command.status(),
        command.externalId(),
        command.roles(),
        command.attributes()));
  }

  @DeleteMapping("/users/{source}/{userId}")
  public void deleteUser(@PathVariable("source") String source, @PathVariable("userId") String userId) {
    securityService.deleteUser(source, userId);
  }

  @GetMapping("/roles")
  public List<RoleView> roles() {
    return securityService.listRoles();
  }

  @PostMapping("/roles")
  public RoleView saveRole(@RequestBody RoleCommand command) {
    return securityService.saveRole(command);
  }

  @PutMapping("/roles/{roleId}")
  public RoleView updateRole(@PathVariable("roleId") String roleId, @RequestBody RoleCommand command) {
    return securityService.saveRole(new RoleCommand(
        roleId,
        command.source(),
        command.name(),
        command.description(),
        command.readOnly(),
        command.privileges(),
        command.roles(),
        command.attributes()));
  }

  @DeleteMapping("/roles/{roleId}")
  public void deleteRole(@PathVariable("roleId") String roleId) {
    securityService.deleteRole(roleId);
  }

  @GetMapping("/privileges")
  public List<PrivilegeView> privileges() {
    return securityService.listPrivileges();
  }

  @PostMapping("/privileges")
  public PrivilegeView savePrivilege(@RequestBody PrivilegeCommand command) {
    return securityService.savePrivilege(command);
  }

  @PutMapping("/privileges/{privilegeId}")
  public PrivilegeView updatePrivilege(
      @PathVariable("privilegeId") String privilegeId,
      @RequestBody PrivilegeCommand command) {
    return securityService.savePrivilege(new PrivilegeCommand(
        privilegeId,
        command.name(),
        command.description(),
        command.type(),
        command.readOnly(),
        command.properties()));
  }

  @DeleteMapping("/privileges/{privilegeId}")
  public void deletePrivilege(@PathVariable("privilegeId") String privilegeId) {
    securityService.deletePrivilege(privilegeId);
  }

  @GetMapping("/content-selectors")
  public List<ContentSelectorView> contentSelectors() {
    return securityService.listRepositoryTargets().stream()
        .filter(this::isContentSelector)
        .map(this::toContentSelectorView)
        .toList();
  }

  @PostMapping("/content-selectors")
  public ContentSelectorView createContentSelector(@RequestBody ContentSelectorCommand command) {
    String name = requireText(command.name(), "name");
    if (securityService.findRepositoryTarget(name).filter(this::isContentSelector).isPresent()) {
      throw new SecurityValidationException("Content selector already exists: " + name);
    }
    requireText(command.expression(), "expression");
    RepositoryTargetView saved = securityService.saveRepositoryTarget(
        new RepositoryTargetCommand(name, name, "*", command.expression(), List.of(),
            Map.of("source", "nexus-content-selector", "type", "csel",
                "description", defaultString(command.description(), ""))));
    return toContentSelectorView(saved);
  }

  @PutMapping("/content-selectors/{name}")
  public ContentSelectorView updateContentSelector(
      @PathVariable("name") String name,
      @RequestBody ContentSelectorCommand command) {
    RepositoryTargetView existing = securityService.findRepositoryTarget(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content selector not found: " + name));
    if (!isContentSelector(existing)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content selector not found: " + name);
    }
    requireText(command.expression(), "expression");
    String type = contentSelectorType(existing);
    RepositoryTargetView saved = securityService.saveRepositoryTarget(
        new RepositoryTargetCommand(name, name, "*", command.expression(), List.of(),
            Map.of("source", "nexus-content-selector", "type", type,
                "description", defaultString(command.description(), ""))));
    return toContentSelectorView(saved);
  }

  @DeleteMapping("/content-selectors/{name}")
  public void deleteContentSelector(@PathVariable("name") String name) {
    RepositoryTargetView existing = securityService.findRepositoryTarget(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content selector not found: " + name));
    if (!isContentSelector(existing)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content selector not found: " + name);
    }
    List<String> usedBy = contentSelectorUsedBy(name);
    if (!usedBy.isEmpty()) {
      throw new SecurityValidationException("Selector is used by privileges: " + String.join(", ", usedBy));
    }
    securityService.deleteRepositoryTarget(name);
  }

  @GetMapping("/realms")
  public List<RealmView> realms() {
    return securityService.listRealms();
  }

  @PutMapping("/realms")
  public List<RealmView> updateRealms(@RequestBody List<RealmCommand> commands) {
    return securityService.saveRealms(commands);
  }

  @GetMapping("/ldap")
  public LdapSettingsView ldapSettings() {
    return securityService.ldapSettings();
  }

  @PutMapping("/ldap")
  public LdapSettingsView updateLdapSettings(@RequestBody(required = false) LdapSettingsCommand command) {
    return securityService.saveLdapSettings(command);
  }

  @GetMapping("/oidc")
  public OidcSettingsView oidcSettings() {
    return securityService.oidcSettings();
  }

  @PutMapping("/oidc")
  public OidcSettingsView updateOidcSettings(@RequestBody OidcSettingsCommand command) {
    return securityService.saveOidcSettings(command);
  }

  @GetMapping("/anonymous")
  public AnonymousSettingsView anonymousSettings() {
    return securityService.anonymousSettings();
  }

  @PutMapping("/anonymous")
  public AnonymousSettingsView updateAnonymousSettings(@RequestBody AnonymousSettingsCommand command) {
    return securityService.saveAnonymousSettings(command);
  }

  @GetMapping("/api-keys")
  public List<ApiKeyView> apiKeys() {
    return securityService.listApiKeys();
  }

  @GetMapping("/api-keys/current")
  public List<ApiKeyView> currentApiKeys(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.listApiKeysForOwner(subject.source(), subject.userId());
  }

  @PostMapping("/api-keys")
  public CreatedApiKeyView createApiKey(@RequestBody ApiKeyCommand command) {
    return securityService.createApiKey(command);
  }

  @PostMapping("/api-keys/current")
  public CreatedApiKeyView createCurrentApiKey(
      HttpServletRequest request,
      @RequestBody(required = false) ApiKeyCommand command) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.createApiKeyForOwner(subject.source(), subject.userId(), command);
  }

  @PostMapping("/api-keys/current/{id}/reset")
  public CreatedApiKeyView resetCurrentApiKey(
      HttpServletRequest request,
      @PathVariable("id") long id) {
    AuthenticatedSubject subject = currentSubject(request);
    return securityService.resetApiKeyForOwner(id, subject.source(), subject.userId());
  }

  @DeleteMapping("/api-keys/{id}")
  public void deleteApiKey(@PathVariable("id") long id) {
    securityService.deleteApiKey(id);
  }

  @DeleteMapping("/api-keys/current/{id}")
  public void deleteCurrentApiKey(HttpServletRequest request, @PathVariable("id") long id) {
    AuthenticatedSubject subject = currentSubject(request);
    securityService.deleteApiKeyForOwner(id, subject.source(), subject.userId());
  }

  private AuthenticatedSubject currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return authenticated;
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  private boolean isContentSelector(RepositoryTargetView target) {
    Map<String, Object> attributes = target.attributes() == null ? Map.of() : target.attributes();
    String source = asString(attributes.get("source"));
    if ("nexus-content-selector".equals(source)) {
      return true;
    }
    String type = asString(attributes.get("type"));
    return type != null
        && target.contentExpression() != null
        && (target.pathPatterns() == null || target.pathPatterns().isEmpty());
  }

  private ContentSelectorView toContentSelectorView(RepositoryTargetView target) {
    Map<String, Object> attributes = target.attributes() == null ? Map.of() : target.attributes();
    String description = asString(attributes.get("description"));
    return new ContentSelectorView(
        target.targetId(),
        description,
        target.contentExpression());
  }

  private String contentSelectorType(RepositoryTargetView target) {
    Map<String, Object> attributes = target.attributes() == null ? Map.of() : target.attributes();
    String type = asString(attributes.get("type"));
    return type != null ? type : "csel";
  }

  private List<String> contentSelectorUsedBy(String selectorName) {
    return securityService.listPrivileges().stream()
        .filter(privilege -> "repository-content-selector".equals(
            privilege.type() != null ? privilege.type() : ""))
        .filter(privilege -> selectorName.equals(contentSelectorName(
            privilege.properties() == null ? Map.of() : privilege.properties())))
        .map(privilege -> privilege.name() != null ? privilege.name() : privilege.privilegeId())
        .toList();
  }

  private static String contentSelectorName(Map<String, Object> properties) {
    return asString(firstPresent(properties, "contentSelector", "selector", "selectorName", "contentSelectorName"));
  }

  private static Object firstPresent(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null) return value;
    }
    return null;
  }

  private static String asString(Object value) {
    if (value instanceof Collection<?> values) {
      return String.join(",", values.stream().map(String::valueOf).toList());
    }
    return value == null ? null : String.valueOf(value);
  }

  private static String requireText(String value, String field) {
    String normalized = value == null ? null : value.isBlank() ? null : value;
    if (normalized == null) {
      throw new SecurityValidationException(field + " is required");
    }
    return normalized;
  }

  private static String defaultString(String value, String fallback) {
    String normalized = value == null ? null : value.isBlank() ? null : value;
    return normalized != null ? normalized : fallback;
  }
}
