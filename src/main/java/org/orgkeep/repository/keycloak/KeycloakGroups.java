package org.orgkeep.repository.keycloak;

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

/**
 * Keycloak groups and group membership. Groups are addressed by id, or by path in {@link #findId}.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakGroups {
    private static final int PAGE_SIZE = 100;

    private final Keycloak keycloak;

    public Optional<String> findId(String realm, String path) {
        try {
            return Optional.of(keycloak.realm(realm).getGroupByPath(path).getId());
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return Optional.empty();
        }
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

    /** @return the id of the new top level group */
    public String createTopLevel(String realm, String name) {
        return createdId(keycloak.realm(realm).groups().add(group(name)));
    }

    /** @return the id of the new sub group */
    public String createChild(String realm, String parentId, String name) {
        return createChild(realm, parentId, name, Map.of());
    }

    /** @return the id of the new sub group */
    public String createChild(String realm, String parentId, String name, Map<String, List<String>> attributes) {
        GroupRepresentation group = group(name);
        group.setAttributes(attributes);
        return createdId(keycloak.realm(realm).groups().group(parentId).subGroup(group));
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

    /** Removes the group and its whole sub tree. */
    public void delete(String realm, String groupId) {
        keycloak.realm(realm).groups().group(groupId).remove();
    }

    public Map<String, List<String>> attributes(String realm, String groupId) {
        Map<String, List<String>> attributes = keycloak.realm(realm).groups().group(groupId).toRepresentation().getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    /** Sets the given attributes on the group, its other attributes are kept. */
    public void putAttributes(String realm, String groupId, Map<String, List<String>> attributes) {
        var group = keycloak.realm(realm).groups().group(groupId);
        GroupRepresentation representation = group.toRepresentation();
        Map<String, List<String>> merged = representation.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(representation.getAttributes());
        merged.putAll(attributes);
        representation.setAttributes(merged);
        group.update(representation);
    }

    /** Replaces all the attributes of the group with these. */
    public void replaceAttributes(String realm, String groupId, Map<String, List<String>> attributes) {
        var group = keycloak.realm(realm).groups().group(groupId);
        GroupRepresentation representation = group.toRepresentation();
        representation.setAttributes(attributes);
        group.update(representation);
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

    private static GroupRepresentation group(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        return group;
    }
}
