package org.orgkeep.fixtures;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class TestRealm implements AutoCloseable {

    private final String name;
    private final RealmResource realm;

    private TestRealm(Keycloak keycloak, String name) {
        this.name = name;
        this.realm = keycloak.realm(name);
    }

    public static TestRealm create(Keycloak keycloak) {
        String name = "test-" + UUID.randomUUID().toString().substring(0, 8);
        RealmRepresentation representation = new RealmRepresentation();
        representation.setRealm(name);
        representation.setEnabled(true);
        representation.setOrganizationsEnabled(true);
        keycloak.realms().create(representation);
        return new TestRealm(keycloak, name);
    }

    public String name() {
        return name;
    }

    public RealmResource resource() {
        return realm;
    }

    public String createUser(String username) {
        return createUser(username, null);
    }

    public String createUser(String username, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setEnabled(true);
        return createUser(user);
    }

    /** Creates the user as given (credentials included), @return its id. */
    public String createUser(UserRepresentation user) {
        try (Response response = realm.users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    /** Creates a bare confidential client, @return its internal id. */
    public String createClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        try (Response response = realm.clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    /** A bare realm doesn't have the roles of Orgkeep (realm-owner...): a test creates the ones it needs. */
    public void createRealmRole(String roleName) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        realm.roles().create(role);
    }

    public void grantRealmRole(String userId, String roleName) {
        RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
        realm.users().get(userId).roles().realmLevel().add(List.of(role));
    }

    /** Grants directly a role of the client clientId (e.g. realm-management/manage-users) to the user. */
    public void grantClientRole(String userId, String clientId, String roleName) {
        String clientUuid = realm.clients().findByClientId(clientId).getFirst().getId();
        RoleRepresentation role = realm.clients().get(clientUuid).roles().get(roleName).toRepresentation();
        realm.users().get(userId).roles().clientLevel(clientUuid).add(List.of(role));
    }

    public void joinGroup(String userId, String groupPath) {
        realm.users().get(userId).joinGroup(groupId(groupPath));
    }

    public void leaveGroup(String userId, String groupPath) {
        realm.users().get(userId).leaveGroup(groupId(groupPath));
    }

    public Set<String> groupPaths(String userId) {
        return realm.users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .collect(Collectors.toSet());
    }

    @Override
    public void close() {
        realm.remove();
    }

    private String groupId(String path) {
        return realm.getGroupByPath(path).getId();
    }
}
