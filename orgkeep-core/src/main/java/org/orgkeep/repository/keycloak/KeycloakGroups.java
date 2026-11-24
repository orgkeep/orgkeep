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
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Keycloak groups and group membership. Groups are addressed by id, or by path in {@link #findId}, whose result is
 * cached (realm + path -> id): a group's id never changes while it exists. The creation and deletion methods keep
 * the cache correct for the groups Orgkeep itself creates or deletes. A group changed outside Orgkeep (the Keycloak
 * console, or a test's raw cleanup) is different: {@link #findId} verifies a cached id still resolves to the same
 * path (one cheap GET by id, instead of trusting the cache blindly) and reads again if it does not, so a stale
 * "found" never silently skips a creation that should run, blocks a read that should 404, or answers for a group
 * renamed or moved to a different path in the meantime. See id-cache.md.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakGroups {
    private static final int PAGE_SIZE = 100;
    /** Cache of the id of a group, keyed by (realm, path). */
    static final String GROUP_ID_CACHE = "group-id";

    private final Keycloak keycloak;
    // @CacheName also registers the cache name at build time (a bean with no @CacheResult / @CacheInvalidate
    // would otherwise go unregistered); lombok.config copies it onto this constructor parameter
    @CacheName(GROUP_ID_CACHE)
    private final Cache groupIdCacheBean;

    /**
     * Not {@code @CacheResult}: only a positive result is cached (a group not found yet is not worth remembering,
     * and could otherwise mask one created right after, outside this method, e.g. by a test's raw fixture), and a
     * cache hit is verified (one GET by id, comparing the path) before being trusted, far cheaper than walking the
     * path again. Neither is possible with a plain interceptor-based cache: nothing runs on a hit, and it caches
     * whatever is returned.
     */
    public Optional<String> findId(String realm, String path) {
        CaffeineCache cache = groupIdCache();
        CompositeCacheKey key = new CompositeCacheKey(realm, path);
        CompletableFuture<String> cached = cache.getIfPresent(key);
        if (cached != null) {
            String id = cached.join();
            if (matches(realm, id, path)) {
                return Optional.of(id);
            }
            cache.invalidate(key).await().indefinitely();
        }
        Optional<String> id = searchId(realm, path);
        id.ifPresent(found -> cache.put(key, CompletableFuture.completedFuture(found)));
        return id;
    }

    private Optional<String> searchId(String realm, String path) {
        try {
            return Optional.of(keycloak.realm(realm).getGroupByPath(path).getId());
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /**
     * Whether the group still exists and still has this path: a cheap GET by id, used to verify an id read from
     * the cache before trusting it. Comparing the path, not just existence, catches a group renamed or moved in
     * the console: a fresh search under the old path would no longer find it. Package-private, not private: tested
     * directly (a spy verifies it is called on a cache hit, instead of a fresh search).
     */
    boolean matches(String realm, String groupId, String path) {
        try {
            GroupRepresentation group = keycloak.realm(realm).groups().group(groupId).toRepresentation();
            return path.equals(group.getPath());
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return false;
        }
    }

    private CaffeineCache groupIdCache() {
        return groupIdCacheBean.as(CaffeineCache.class);
    }

    /** The group at this path, full representation: attributes included. */
    public Optional<GroupRepresentation> find(String realm, String path) {
        try {
            return Optional.of(keycloak.realm(realm).getGroupByPath(path));
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** @return the id of the new top level group, e.g. "/acme" */
    public String createTopLevel(String realm, String name) {
        String id = createdId(keycloak.realm(realm).groups().add(group(name)));
        evictId(realm, "/" + name);
        return id;
    }

    /**
     * @param path the path of the new group, e.g. "/acme/user-groups" (its id is cached by {@link #findId}, keyed
     *             by this path: the Keycloak call below only has the id of the parent, not its path)
     * @return the id of the new sub group
     */
    @CacheInvalidate(cacheName = GROUP_ID_CACHE)
    public String createChild(@CacheKey String realm, @CacheKey String path, String parentId, String name) {
        return createdId(keycloak.realm(realm).groups().group(parentId).subGroup(group(name)));
    }

    /** @param path the path of the new group, see {@link #createChild(String, String, String, String)}
     * @return the id of the new sub group */
    @CacheInvalidate(cacheName = GROUP_ID_CACHE)
    public String createChild(@CacheKey String realm, @CacheKey String path, String parentId, String name,
                               Map<String, List<String>> attributes) {
        GroupRepresentation group = group(name);
        group.setAttributes(attributes);
        return createdId(keycloak.realm(realm).groups().group(parentId).subGroup(group));
    }

    /**
     * Like {@link #createChild(String, String, String, String, Map)}, but a name already taken under the parent is
     * not an error: Keycloak answers 409, which gives an empty result.
     *
     * @param path the path of the new group, see {@link #createChild(String, String, String, String)}
     * @return the id of the new sub group, empty if the parent already has a sub group of this name
     */
    @CacheInvalidate(cacheName = GROUP_ID_CACHE)
    public Optional<String> createChildIfAbsent(@CacheKey String realm, @CacheKey String path, String parentId,
                                                 String name, Map<String, List<String>> attributes) {
        GroupRepresentation group = group(name);
        group.setAttributes(attributes);
        try (Response response = keycloak.realm(realm).groups().group(parentId).subGroup(group)) {
            if (KeycloakErrors.isConflict(response)) {
                return Optional.empty();
            }
            return Optional.of(CreatedResponseUtil.getCreatedId(response));
        }
    }

    /** Direct sub groups, full representations: attributes included. */
    public List<GroupRepresentation> children(String realm, String parentId) {
        // Keycloak pages sub groups (10 by default): read every page
        List<GroupRepresentation> children = new ArrayList<>();
        List<GroupRepresentation> page;
        int first = 0;
        do {
            page = keycloak.realm(realm).groups().group(parentId).getSubGroups(first, PAGE_SIZE, false);
            children.addAll(page);
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return children;
    }

    /** Removes the group and its whole sub tree, evicting its cached id. */
    @CacheInvalidate(cacheName = GROUP_ID_CACHE)
    public void delete(@CacheKey String realm, @CacheKey String path, String groupId) {
        keycloak.realm(realm).groups().group(groupId).remove();
    }

    public Map<String, List<String>> attributes(String realm, String groupId) {
        Map<String, List<String>> attributes = keycloak.realm(realm).groups().group(groupId).toRepresentation().getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    /** Replaces all the attributes of the group with these. */
    public void replaceAttributes(String realm, String groupId, Map<String, List<String>> attributes) {
        replaceAttributes(realm, keycloak.realm(realm).groups().group(groupId).toRepresentation(), attributes);
    }

    /**
     * Like {@link #replaceAttributes(String, String, Map)}, but writes straight on this representation instead of
     * reading the group again: for a caller that already holds one, read under the lock that protects the write
     * (a stale representation, e.g. read before the lock was taken, must not be passed here). Sets the given
     * attributes on {@code group} itself before writing it back: the representation is mutated, callers must not
     * rely on its previous attributes afterward.
     */
    public void replaceAttributes(String realm, GroupRepresentation group, Map<String, List<String>> attributes) {
        group.setAttributes(attributes);
        keycloak.realm(realm).groups().group(group.getId()).update(group);
    }

    /**
     * Groups the user belongs to directly. These are brief representations:
     * id, name and path are set, attributes are not (see {@link #attributes}).
     */
    public List<GroupRepresentation> groupsOf(String realm, String userId) {
        return keycloak.realm(realm).users().get(userId).groups();
    }

    /** Ids of the direct members of the group, not of its sub groups. */
    public Set<String> memberIds(String realm, String groupId) {
        // Keycloak pages the members (100 by default): read every page
        Set<String> ids = new HashSet<>();
        List<UserRepresentation> page;
        int first = 0;
        do {
            page = keycloak.realm(realm).groups().group(groupId).members(first, PAGE_SIZE, true);
            page.forEach(user -> ids.add(user.getId()));
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return ids;
    }

    /** The direct members of the group, not of its sub groups, brief representations (id, username, enabled). */
    public List<UserRepresentation> members(String realm, String groupId) {
        // Keycloak pages the members (100 by default): read every page
        List<UserRepresentation> members = new ArrayList<>();
        List<UserRepresentation> page;
        int first = 0;
        do {
            page = keycloak.realm(realm).groups().group(groupId).members(first, PAGE_SIZE, true);
            members.addAll(page);
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return members;
    }

    /**
     * One page of the direct members of the group, not of its sub groups, full representation (requiredActions
     * and emailVerified included, spike Q4): the page a list screen shows, no more.
     */
    public List<UserRepresentation> membersPage(String realm, String groupId, int first, int max) {
        return keycloak.realm(realm).groups().group(groupId).members(first, max, false);
    }

    /**
     * Like {@link #members(String, String)}, but full representation (requiredActions and emailVerified
     * included): every page, for a group small enough to read whole and slice afterwards (a team).
     */
    public List<UserRepresentation> membersFull(String realm, String groupId) {
        List<UserRepresentation> members = new ArrayList<>();
        List<UserRepresentation> page;
        int first = 0;
        do {
            page = keycloak.realm(realm).groups().group(groupId).members(first, PAGE_SIZE, false);
            members.addAll(page);
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return members;
    }

    /**
     * The client roles granted directly to the group, for every client in one call: clientId → role names.
     */
    public Map<String, Set<String>> clientRoles(String realm, String groupId) {
        var clientMappings = keycloak.realm(realm).groups().group(groupId).roles().getAll().getClientMappings();
        Map<String, Set<String>> roles = new HashMap<>();
        if (clientMappings != null) {
            clientMappings.forEach((clientId, mappings) -> {
                Set<String> names = new HashSet<>();
                if (mappings.getMappings() != null) {
                    mappings.getMappings().forEach(role -> names.add(role.getName()));
                }
                roles.put(clientId, names);
            });
        }
        return roles;
    }

    public void join(String realm, String userId, String groupId) {
        keycloak.realm(realm).users().get(userId).joinGroup(groupId);
    }

    public void leave(String realm, String userId, String groupId) {
        keycloak.realm(realm).users().get(userId).leaveGroup(groupId);
    }

    private static String createdId(Response response) {
        try(response){
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    /** Evicts the cached id of the group at this path: used where the eviction cannot be expressed as a method parameter. */
    private void evictId(String realm, String path) {
        groupIdCacheBean.invalidate(new CompositeCacheKey(realm, path)).await().indefinitely();
    }

    private static GroupRepresentation group(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        return group;
    }
}
