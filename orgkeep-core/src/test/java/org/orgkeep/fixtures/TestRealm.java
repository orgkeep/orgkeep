package org.orgkeep.fixtures;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.ConfigProvider;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserProfileResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
public class TestRealm implements AutoCloseable {

    // Deleting a realm is a REST round-trip to Keycloak that the next test does not need to wait for: close() hands
    // it to this pool and returns immediately. One virtual thread per deletion, fire-and-forget (no shutdown: this
    // pool lives as long as the test JVM).
    private static final ExecutorService CLEANUP = Executors.newVirtualThreadPerTaskExecutor();

    private final String name;
    private final Keycloak keycloak;
    private final RealmResource realm;

    private TestRealm(Keycloak keycloak, String name) {
        this.keycloak = keycloak;
        this.name = name;
        this.realm = keycloak.realm(name);
    }

    /**
     * Opens its own admin client of the master realm (credentials from KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD,
     * admin/admin by default, the Dev Services defaults), instead of the application's own singleton (see
     * KeycloakAdminClientProducer). This is a plain fixture outside CDI: it owns and closes its own client, with a
     * lifecycle independent from the application's (the whole test class, @BeforeAll to @AfterAll, not an HTTP
     * request).
     */
    public static TestRealm create() {
        String name = "test-" + UUID.randomUUID().toString().substring(0, 8);
        var config = ConfigProvider.getConfig();
        String serverUrl = config.getValue("quarkus.keycloak.admin-client.server-url", String.class);
        // the bootstrap credentials of master (see BootstrapCredentialsSource), admin/admin in dev and test
        String adminUser = config.getOptionalValue("KEYCLOAK_ADMIN_USER", String.class).orElse("admin");
        String adminPassword = config.getOptionalValue("KEYCLOAK_ADMIN_PASSWORD", String.class).orElse("admin");
        Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")
                .clientId("admin-cli")
                .grantType(OAuth2Constants.PASSWORD)
                .username(adminUser)
                .password(adminPassword)
                .build();
        try {
            RealmRepresentation representation = new RealmRepresentation();
            representation.setRealm(name);
            representation.setEnabled(true);
            representation.setOrganizationsEnabled(true);
            keycloak.realms().create(representation);
        } catch (RuntimeException e) {
            keycloak.close();
            throw e;
        }
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

    /**
     * A bare realm doesn't have the roles of Orgkeep (realm-owner...): a test ensures the ones it needs exist.
     * Idempotent: with one realm shared by every test of a class, more than one test may ask for the same role.
     */
    public void createRealmRole(String roleName) {
        try {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(roleName);
            realm.roles().create(role);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() != Response.Status.CONFLICT.getStatusCode()) {
                throw e;
            }
        }
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

    /**
     * Declares the four suspension attributes (see Suspensions) in the user profile, like
     * RealmProvisionerImpl.completeCreation does at realm creation: this fixture builds its realm through
     * ensureDefaults only (see RealmProvisionerTest for the full creation flow), so tests of the suspension
     * feature call this once their org is set up. Idempotent.
     */
    public void declareSuspensionAttributes() {
        UserProfileResource userProfile = realm.users().userProfile();
        UPConfig config = userProfile.getConfiguration();
        boolean changed = false;
        for (String name : List.of("suspension-reason", "suspended-at", "suspended-by", "suspension-org-choice")) {
            if (config.getAttribute(name) != null) {
                continue;
            }
            UPAttribute attribute = new UPAttribute(name, new UPAttributePermissions(Set.of("admin"), Set.of("admin")));
            if ("suspension-reason".equals(name)) {
                Map<String, Object> maxLength = new HashMap<>();
                maxLength.put("max", 500);
                attribute.addValidation("length", maxLength);
            }
            config.addOrReplaceAttribute(attribute);
            changed = true;
        }
        if (changed) {
            userProfile.update(config);
        }
    }

    /** Removes the four suspension attributes: simulates a realm created before the feature existed. */
    public void removeSuspensionAttributes() {
        UserProfileResource userProfile = realm.users().userProfile();
        UPConfig config = userProfile.getConfiguration();
        boolean changed = false;
        for (String name : List.of("suspension-reason", "suspended-at", "suspended-by", "suspension-org-choice")) {
            changed |= config.removeAttribute(name);
        }
        if (changed) {
            userProfile.update(config);
        }
    }

    public Set<String> groupPaths(String userId) {
        return realm.users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .collect(Collectors.toSet());
    }

    /** Deletes the realm in the background: the caller (and the next test) does not wait for it. */
    @Override
    public void close() {
        CLEANUP.submit(() -> {
            try {
                realm.remove();
            } catch (RuntimeException e) {
                log.warn("Failed to delete the throwaway realm '{}'", name, e);
            } finally {
                keycloak.close();
            }
        });
    }

    private String groupId(String path) {
        return realm.getGroupByPath(path).getId();
    }
}
