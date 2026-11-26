package org.orgkeep.repository.keycloak;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Keycloak clients and their client roles.
 * Clients are addressed by their internal id ("uuid"), except in {@link #findUuid}, whose result is cached
 * (realm + clientId -> uuid): a client's uuid never changes while it exists. {@link #create} and {@link #delete}
 * keep the cache correct for the clients Orgkeep itself creates or deletes. A client changed outside Orgkeep (the
 * Keycloak console, or a test's raw cleanup) is different: {@link #findUuid} verifies a cached uuid still resolves
 * to the same clientId (one cheap GET by id, instead of trusting the cache blindly) and reads again if it does
 * not, so a stale "found" never silently skips a creation that should run, blocks a read that should 404, or
 * answers for a client renamed to something else in the meantime. See id-cache.md.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakClients {
    private static final int PAGE_SIZE = 100;
    /** Attribute of a client read by the theme selector of Keycloak, before the login theme of the realm. */
    private static final String LOGIN_THEME = "login_theme";
    /** Cache of the internal id of a client, keyed by (realm, clientId): looked up by many requests, its id never changes. */
    static final String CLIENT_UUID_CACHE = "client-uuid";

    private final Keycloak keycloak;
    // @CacheName also registers the cache name at build time (a bean with no @CacheResult / @CacheInvalidate
    // would otherwise go unregistered); lombok.config copies it onto this constructor parameter
    @CacheName(CLIENT_UUID_CACHE)
    private final Cache clientUuidCacheBean;

    /**
     * Not {@code @CacheResult}: only a positive result is cached (a client not found yet is not worth remembering,
     * and could otherwise mask one created right after, outside this method, e.g. by a test's raw fixture), and a
     * cache hit is verified (one GET by id, comparing the clientId) before being trusted, far cheaper than
     * searching again. Neither is possible with a plain interceptor-based cache: nothing runs on a hit, and it
     * caches whatever is returned.
     */
    public Optional<String> findUuid(String realm, String clientId) {
        CaffeineCache cache = clientUuidCache();
        CompositeCacheKey key = new CompositeCacheKey(realm, clientId);
        CompletableFuture<String> cached = cache.getIfPresent(key);
        if (cached != null) {
            String uuid = cached.join();
            if (matches(realm, uuid, clientId)) {
                return Optional.of(uuid);
            }
            cache.invalidate(key).await().indefinitely();
        }
        Optional<String> uuid = searchUuid(realm, clientId);
        uuid.ifPresent(found -> cache.put(key, CompletableFuture.completedFuture(found)));
        return uuid;
    }

    private Optional<String> searchUuid(String realm, String clientId) {
        return keycloak.realm(realm).clients().findByClientId(clientId).stream()
                .findFirst()
                .map(ClientRepresentation::getId);
    }

    /**
     * Whether the client still exists and still has this clientId: a cheap GET by id, used to verify an id read
     * from a cache before trusting it (here, and by {@link org.orgkeep.components.catalog.CatalogSetup#get}, whose
     * own cache holds a client uuid). Comparing the clientId, not just existence, catches a client renamed in the
     * console: a fresh search under the old clientId would no longer find it.
     */
    public boolean matches(String realm, String clientUuid, String clientId) {
        try {
            ClientRepresentation client = keycloak.realm(realm).clients().get(clientUuid).toRepresentation();
            return clientId.equals(client.getClientId());
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return false;
        }
    }

    private CaffeineCache clientUuidCache() {
        return clientUuidCacheBean.as(CaffeineCache.class);
    }

    /** Full representation of the client, attributes included. */
    public Optional<ClientRepresentation> find(String realm, String clientId) {
        return keycloak.realm(realm).clients().findByClientId(clientId).stream()
                .findFirst();
    }

    /** @return the internal id of the new client, empty if one with this clientId already exists */
    public Optional<String> create(String realm, ClientRepresentation client) {
        Optional<String> uuid;
        try(Response response = keycloak.realm(realm).clients().create(client)){
            if(KeycloakErrors.isConflict(response)){
                uuid = Optional.empty();
            } else {
                uuid = Optional.of(CreatedResponseUtil.getCreatedId(response));
            }
        }
        // a stale positive entry left by a deletion done outside Orgkeep must not survive this creation
        evictUuid(realm, client.getClientId());
        return uuid;
    }

    /**
     * The access token the client would issue to the user, as Keycloak computes it for the "Client scopes > Evaluate"
     * tab of the console: the claims only, never signed, so nothing usable as a bearer token. The user does not log in.
     *
     * @param scope the scope parameter an application would send, e.g. "openid prod"
     */
    public AccessToken exampleAccessToken(String realm, String clientUuid, String userId, String scope) {
        // no audience: the token is not restricted to one, as when an application asks for it
        return keycloak.realm(realm).clients().get(clientUuid).clientScopesEvaluate()
                .generateAccessToken(scope, userId, null);
    }

    /** Full representation of the client. */
    public ClientRepresentation get(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).toRepresentation();
    }

    /** Replaces the client with this representation, read with {@link #get} and modified. */
    public void update(String realm, String clientUuid, ClientRepresentation client) {
        keycloak.realm(realm).clients().get(clientUuid).update(client);
    }

    /** "Login theme" of the settings of the client in the console: replaces the theme of the realm for its logins. */
    public void setLoginTheme(String realm, String clientUuid, String theme) {
        ClientRepresentation client = get(realm, clientUuid);
        Map<String, String> attributes = client.getAttributes() == null ? new HashMap<>() : new HashMap<>(client.getAttributes());
        attributes.put(LOGIN_THEME, theme);
        client.setAttributes(attributes);
        update(realm, clientUuid, client);
    }

    /** Deletes the client and evicts its cached uuid (the clientId, not the one just deleted, if it is reused). */
    @CacheInvalidate(cacheName = CLIENT_UUID_CACHE)
    public void delete(@CacheKey String realm, @CacheKey String clientId, String clientUuid) {
        keycloak.realm(realm).clients().get(clientUuid).remove();
    }

    /** Evicts the cached uuid of the client: used where the eviction cannot be expressed as a method parameter. */
    private void evictUuid(String realm, String clientId) {
        clientUuidCacheBean.invalidate(new CompositeCacheKey(realm, clientId)).await().indefinitely();
    }

    public void createRole(String realm, String clientUuid, String roleName, String description) {
        roles(realm, clientUuid).create(new RoleRepresentation(roleName, description, false));
    }

    /** Ids of the default client scopes of the client. */
    public Set<String> defaultScopeIds(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).getDefaultClientScopes().stream()
                .map(ClientScopeRepresentation::getId)
                .collect(Collectors.toSet());
    }

    /** Ids of the optional client scopes of the client. */
    public Set<String> optionalScopeIds(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).getOptionalClientScopes().stream()
                .map(ClientScopeRepresentation::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Makes the client scope a default scope of the client: in every token, whatever the "scope" parameter.
     * Idempotent, an optional scope becomes a default one (Keycloak refuses a scope that is both).
     */
    public void makeDefaultScope(String realm, String clientUuid, String scopeId) {
        var client = keycloak.realm(realm).clients().get(clientUuid);
        if (optionalScopeIds(realm, clientUuid).contains(scopeId)) {
            client.removeOptionalClientScope(scopeId);
        }
        if (!defaultScopeIds(realm, clientUuid).contains(scopeId)) {
            client.addDefaultClientScope(scopeId);
        }
    }

    /**
     * Makes the client scope an optional scope of the client: in a token only when the application asks for it
     * ("scope" parameter). Idempotent, a default scope becomes an optional one.
     */
    public void makeOptionalScope(String realm, String clientUuid, String scopeId) {
        var client = keycloak.realm(realm).clients().get(clientUuid);
        if (defaultScopeIds(realm, clientUuid).contains(scopeId)) {
            client.removeDefaultClientScope(scopeId);
        }
        if (!optionalScopeIds(realm, clientUuid).contains(scopeId)) {
            client.addOptionalClientScope(scopeId);
        }
    }

    /**
     * Keeps only the default client scopes of these names and removes every optional one: the client starts from what
     * the realm gave it and is reduced to the scopes it needs. Idempotent.
     */
    public void reduceScopes(String realm, String clientUuid, Set<String> keptDefaultScopeNames) {
        var client = keycloak.realm(realm).clients().get(clientUuid);
        client.getDefaultClientScopes().stream()
                .filter(scope -> !keptDefaultScopeNames.contains(scope.getName()))
                .forEach(scope -> client.removeDefaultClientScope(scope.getId()));
        client.getOptionalClientScopes()
                .forEach(scope -> client.removeOptionalClientScope(scope.getId()));
    }

    /** The service-account user of the client, empty if the client has no service account. */
    public Optional<UserRepresentation> serviceAccountUser(String realm, String clientUuid) {
        try {
            return Optional.ofNullable(keycloak.realm(realm).clients().get(clientUuid).getServiceAccountUser());
        } catch (WebApplicationException e) {
            // 400 when the service account is not enabled on the client
            boolean notEnabled = e.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode();
            if (!KeycloakErrors.isNotFound(e) && !notEnabled) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** Removes the client scope from the client, default or optional. Idempotent. */
    public void removeScope(String realm, String clientUuid, String scopeId) {
        var client = keycloak.realm(realm).clients().get(clientUuid);
        if (defaultScopeIds(realm, clientUuid).contains(scopeId)) {
            client.removeDefaultClientScope(scopeId);
        }
        if (optionalScopeIds(realm, clientUuid).contains(scopeId)) {
            client.removeOptionalClientScope(scopeId);
        }
    }

    /**
     * Gives the target client the default and optional client scopes of the source client, except the excluded ones.
     * Idempotent: only the differences are written.
     */
    public void copyScopes(String realm, String sourceUuid, String targetUuid, Set<String> excludedScopeIds) {
        Set<String> wantedDefault = new HashSet<>(defaultScopeIds(realm, sourceUuid));
        Set<String> wantedOptional = new HashSet<>(optionalScopeIds(realm, sourceUuid));
        wantedDefault.removeAll(excludedScopeIds);
        wantedOptional.removeAll(excludedScopeIds);
        Set<String> currentDefault = defaultScopeIds(realm, targetUuid);
        Set<String> currentOptional = optionalScopeIds(realm, targetUuid);

        var target = keycloak.realm(realm).clients().get(targetUuid);
        // removals first: Keycloak refuses a scope that is both default and optional
        currentDefault.stream().filter(id -> !wantedDefault.contains(id)).forEach(target::removeDefaultClientScope);
        currentOptional.stream().filter(id -> !wantedOptional.contains(id)).forEach(target::removeOptionalClientScope);
        wantedDefault.stream().filter(id -> !currentDefault.contains(id)).forEach(target::addDefaultClientScope);
        wantedOptional.stream().filter(id -> !currentOptional.contains(id)).forEach(target::addOptionalClientScope);
    }

    /**
     * Gives the target client the scope mappings of the source client: its realm roles and the roles of every other
     * client. Idempotent: only the differences are written.
     */
    public void copyScopeMappings(String realm, String sourceUuid, String targetUuid) {
        MappingsRepresentation wanted = keycloak.realm(realm).clients().get(sourceUuid).getScopeMappings().getAll();
        var target = keycloak.realm(realm).clients().get(targetUuid).getScopeMappings();
        MappingsRepresentation current = target.getAll();

        alignRoles(target.realmLevel(), orEmpty(wanted.getRealmMappings()), orEmpty(current.getRealmMappings()));

        // keyed by the clientId of the client holding the roles; the representation carries its internal id
        Map<String, ClientMappingsRepresentation> wantedClients = orEmpty(wanted.getClientMappings());
        Map<String, ClientMappingsRepresentation> currentClients = orEmpty(current.getClientMappings());
        Set<String> clientIds = new HashSet<>(wantedClients.keySet());
        clientIds.addAll(currentClients.keySet());
        for (String clientId : clientIds) {
            ClientMappingsRepresentation wantedRoles = wantedClients.get(clientId);
            ClientMappingsRepresentation currentRoles = currentClients.get(clientId);
            String roleClientUuid = (wantedRoles != null ? wantedRoles : currentRoles).getId();
            alignRoles(target.clientLevel(roleClientUuid),
                    wantedRoles == null ? List.of() : orEmpty(wantedRoles.getMappings()),
                    currentRoles == null ? List.of() : orEmpty(currentRoles.getMappings()));
        }
    }

    /** Adds the missing roles to the scope mappings, removes the extra ones (compared by role id). */
    private static void alignRoles(RoleScopeResource scope, List<RoleRepresentation> wanted, List<RoleRepresentation> current) {
        Set<String> wantedIds = wanted.stream().map(RoleRepresentation::getId).collect(Collectors.toSet());
        Set<String> currentIds = current.stream().map(RoleRepresentation::getId).collect(Collectors.toSet());
        List<RoleRepresentation> extra = current.stream().filter(role -> !wantedIds.contains(role.getId())).toList();
        List<RoleRepresentation> missing = wanted.stream().filter(role -> !currentIds.contains(role.getId())).toList();
        if (!extra.isEmpty()) {
            scope.remove(extra);
        }
        if (!missing.isEmpty()) {
            scope.add(missing);
        }
    }

    // the admin API sends null rather than an empty list or map
    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    /** The current secret of the confidential client, empty when Keycloak has none (public or bearer-only client). */
    public Optional<String> secret(String realm, String clientUuid) {
        return Optional.ofNullable(keycloak.realm(realm).clients().get(clientUuid).getSecret())
                .map(credential -> credential.getValue())
                .filter(value -> !value.isBlank());
    }

    /** Generates a new secret for the confidential client: the previous one stops working at once. */
    public String regenerateSecret(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).generateNewSecret().getValue();
    }

    /**
     * Scope mappings of the client for these realm roles: with fullScopeAllowed=false, only the roles in the scope of
     * the client (its own scope mappings and those of its client scopes) end up in its tokens. Idempotent.
     */
    public void addRealmRolesToScope(String realm, String clientUuid, Collection<String> roleNames) {
        var realmLevel = keycloak.realm(realm).clients().get(clientUuid).getScopeMappings().realmLevel();
        Set<String> mapped = realmLevel.listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        // a role the realm does not have is skipped (a realm not provisioned by Orgkeep)
        List<RoleRepresentation> missing = keycloak.realm(realm).roles().list(true).stream()
                .filter(role -> roleNames.contains(role.getName()) && !mapped.contains(role.getName()))
                .toList();
        if (!missing.isEmpty()) {
            realmLevel.add(missing);
        }
    }

    /** Scope mappings of the client for every role of another client (roleClientUuid). Idempotent. */
    public void addClientRolesToScope(String realm, String clientUuid, String roleClientUuid) {
        List<RoleRepresentation> roles = roles(realm, roleClientUuid).list(true);
        if (!roles.isEmpty()) {
            // Keycloak ignores the roles already mapped
            keycloak.realm(realm).clients().get(clientUuid).getScopeMappings().clientLevel(roleClientUuid).add(roles);
        }
    }

    /** @return false if the client already has a role with this name */
    public boolean createRole(String realm, String clientUuid, RoleRepresentation role) {
        try {
            roles(realm, clientUuid).create(role);
            return true;
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isConflict(e)) {
                throw e;
            }
            return false;
        }
    }

    /** Full representation of the role, attributes included. */
    public Optional<RoleRepresentation> findRole(String realm, String clientUuid, String roleName) {
        try {
            return Optional.of(roles(realm, clientUuid).get(roleName).toRepresentation());
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** Roles of the client, full representations: attributes included. */
    public List<RoleRepresentation> listRoles(String realm, String clientUuid) {
        return roles(realm, clientUuid).list(false);
    }

    public int countRoles(String realm, String clientUuid) {
        // brief representations: the attributes are not needed to count
        return roles(realm, clientUuid).list(true).size();
    }

    /** Replaces the role with this representation, attributes included. */
    public void updateRole(String realm, String clientUuid, String roleName, RoleRepresentation role) {
        roles(realm, clientUuid).get(roleName).update(role);
    }

    public void deleteRole(String realm, String clientUuid, String roleName) {
        roles(realm, clientUuid).deleteRole(roleName);
    }

    /** Makes parentRole a composite including childRole. */
    public void addComposite(String realm, String clientUuid, String parentRole, String childRole) {
        RolesResource roles = roles(realm, clientUuid);
        roles.get(parentRole).addComposites(List.of(roles.get(childRole).toRepresentation()));
    }

    public void grantToUser(String realm, String clientUuid, String userId, String roleName) {
        keycloak.realm(realm).users().get(userId).roles().clientLevel(clientUuid).add(List.of(role(realm, clientUuid, roleName)));
    }

    public void grantToGroup(String realm, String clientUuid, String groupId, String roleName) {
        keycloak.realm(realm).groups().group(groupId).roles().clientLevel(clientUuid).add(List.of(role(realm, clientUuid, roleName)));
    }

    /** @param roles representations read from Keycloak: the id of each role is needed */
    public void grantToGroup(String realm, String clientUuid, String groupId, Collection<RoleRepresentation> roles) {
        if (!roles.isEmpty()) {
            keycloak.realm(realm).groups().group(groupId).roles().clientLevel(clientUuid).add(List.copyOf(roles));
        }
    }

    /** Roles of this client granted directly to the group, brief representations. */
    public List<RoleRepresentation> rolesOfGroup(String realm, String clientUuid, String groupId) {
        return keycloak.realm(realm).groups().group(groupId).roles().clientLevel(clientUuid).listAll();
    }

    /** @param roles representations read from Keycloak, e.g. by {@link #rolesOfGroup} */
    public void revokeFromGroup(String realm, String clientUuid, String groupId, Collection<RoleRepresentation> roles) {
        if (!roles.isEmpty()) {
            keycloak.realm(realm).groups().group(groupId).roles().clientLevel(clientUuid).remove(List.copyOf(roles));
        }
    }

    /** Revokes the role granted directly to the group, if granted. */
    public void revokeFromGroup(String realm, String clientUuid, String groupId, String roleName) {
        revokeFromGroup(realm, clientUuid, groupId, rolesOfGroup(realm, clientUuid, groupId).stream()
                .filter(role -> roleName.equals(role.getName()))
                .toList());
    }

    /** Names of the roles of this client granted directly to the user (not through a group or a composite). */
    public Set<String> directRolesOf(String realm, String clientUuid, String userId) {
        return keycloak.realm(realm).users().get(userId).roles().clientLevel(clientUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** Revokes the role granted directly to the user, if granted. */
    public void revokeFromUser(String realm, String clientUuid, String userId, String roleName) {
        var userRoles = keycloak.realm(realm).users().get(userId).roles().clientLevel(clientUuid);
        List<RoleRepresentation> granted = userRoles.listAll().stream()
                .filter(role -> roleName.equals(role.getName()))
                .toList();
        if (!granted.isEmpty()) {
            userRoles.remove(granted);
        }
    }

    /** Revokes every role of this client granted directly to the user. */
    public void revokeAllFromUser(String realm, String clientUuid, String userId) {
        var userRoles = keycloak.realm(realm).users().get(userId).roles().clientLevel(clientUuid);
        List<RoleRepresentation> granted = userRoles.listAll();
        if (!granted.isEmpty()) {
            userRoles.remove(granted);
        }
    }

    /** Users holding the role directly (not through a group or a composite), brief representations. */
    public List<UserRepresentation> listUsersWithRole(String realm, String clientUuid, String roleName) {
        return roles(realm, clientUuid).get(roleName).getUserMembers();
    }

    /**
     * Like {@link #listUsersWithRole}, but full representations (suspension attributes included), every page:
     * for a caller that needs to show the suspension of the owners (OrgMemberServiceImpl.list, OrgServiceImpl.owners).
     * Other callers of listUsersWithRole keep the brief, one-call read.
     */
    public List<UserRepresentation> listUsersWithRoleFull(String realm, String clientUuid, String roleName) {
        List<UserRepresentation> holders = new ArrayList<>();
        int first = 0;
        List<UserRepresentation> page;
        do {
            page = roles(realm, clientUuid).get(roleName).getUserMembers(false, first, PAGE_SIZE);
            holders.addAll(page);
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return holders;
    }

    /** Ids of the groups holding the role directly. */
    /**
     * Paths of all the groups holding the role directly. Keycloak answers 100 groups at most per call:
     * pages are read until a short one.
     */
    public List<String> groupPathsWithRole(String realm, String clientUuid, String roleName) {
        return listGroupsWithRole(realm, clientUuid, roleName).stream()
                .map(GroupRepresentation::getPath)
                .toList();
    }

    /**
     * All the groups holding the role directly, brief representations (id, name, path). Keycloak answers 100 groups
     * at most per call: pages are read until a short one.
     */
    public List<GroupRepresentation> listGroupsWithRole(String realm, String clientUuid, String roleName) {
        List<GroupRepresentation> groups = new ArrayList<>();
        int first = 0;
        Set<GroupRepresentation> page;
        do {
            page = roles(realm, clientUuid).get(roleName).getRoleGroupMembers(first, PAGE_SIZE);
            groups.addAll(page);
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return groups;
    }

    public Set<String> groupsWithRole(String realm, String clientUuid, String roleName) {
        return roles(realm, clientUuid).get(roleName).getRoleGroupMembers().stream()
                .map(GroupRepresentation::getId)
                .collect(Collectors.toSet());
    }

    private RoleRepresentation role(String realm, String clientUuid, String roleName) {
        return roles(realm, clientUuid).get(roleName).toRepresentation();
    }

    private RolesResource roles(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).roles();
    }
}
