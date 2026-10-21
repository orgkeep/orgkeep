package io.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keycloak clients and their client roles.
 * Clients are addressed by their internal id ("uuid"), except in {@link #findUuid}.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakClients {
    private static final int PAGE_SIZE = 100;

    private final Keycloak keycloak;

    public Optional<String> findUuid(String realm, String clientId) {
        return keycloak.realm(realm).clients().findByClientId(clientId).stream()
                .findFirst()
                .map(ClientRepresentation::getId);
    }

    /** Full representation of the client, attributes included. */
    public Optional<ClientRepresentation> find(String realm, String clientId) {
        return keycloak.realm(realm).clients().findByClientId(clientId).stream()
                .findFirst();
    }

    /** @return the internal id of the new client, empty if one with this clientId already exists */
    public Optional<String> create(String realm, ClientRepresentation client) {
        try(Response response = keycloak.realm(realm).clients().create(client)){
            if(KeycloakErrors.isConflict(response)){
                return Optional.empty();
            }
            return Optional.of(CreatedResponseUtil.getCreatedId(response));
        }
    }

    /** Full representation of the client. */
    public ClientRepresentation get(String realm, String clientUuid) {
        return keycloak.realm(realm).clients().get(clientUuid).toRepresentation();
    }

    /** Replaces the client with this representation, read with {@link #get} and modified. */
    public void update(String realm, String clientUuid, ClientRepresentation client) {
        keycloak.realm(realm).clients().get(clientUuid).update(client);
    }

    public void delete(String realm, String clientUuid) {
        keycloak.realm(realm).clients().get(clientUuid).remove();
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

    /** Ids of the users holding the role directly. */
    public Set<String> usersWithRole(String realm, String clientUuid, String roleName) {
        return listUsersWithRole(realm, clientUuid, roleName).stream()
                .map(UserRepresentation::getId)
                .collect(Collectors.toSet());
    }

    /** Users holding the role directly (not through a group or a composite), brief representations. */
    public List<UserRepresentation> listUsersWithRole(String realm, String clientUuid, String roleName) {
        return roles(realm, clientUuid).get(roleName).getUserMembers();
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
