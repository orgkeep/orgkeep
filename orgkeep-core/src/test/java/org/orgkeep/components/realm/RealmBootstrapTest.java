package org.orgkeep.components.realm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.CredentialRepresentation;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.exceptions.RealmAlreadyExistsException;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.CreateRealmResponse;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creates a realm the way the first startup does, with the admin of master of Dev Services (the runtime client of the
 * tests is on master), then checks what Keycloak holds and what the new client can and cannot do.
 */
@QuarkusTest
class RealmBootstrapTest {
    private static final String CLIENT_ID = "orgkeep-admin";
    private static final String SECRET = "a-test-secret";

    @Inject
    RealmBootstrap realmBootstrap;

    @Inject
    KeycloakAdminAccess adminAccess;

    // the runtime client of the tests: an admin of master
    @Inject
    Keycloak master;

    private String realmName;

    @BeforeEach
    void setUp() {
        realmName = "boot-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        try {
            master.realm(realmName).remove();
        } catch (WebApplicationException ignored) {
            // the test did not create it
        }
    }

    private CreateRealmResponse create() {
        return realmBootstrap.createWithBootstrapAdmin(
                new CreateRealmRequest(realmName, "owner@boot.test", "s3cret"),
                new AdminClient(CLIENT_ID, SECRET),
                new BootstrapCredentials("admin", "admin"));
    }

    @Test
    void createsTheAdminClientWithTheGivenSecret() {
        create();

        RealmResource realm = master.realm(realmName);
        ClientRepresentation client = realm.clients().findByClientId(CLIENT_ID).get(0);
        assertFalse(client.isPublicClient());
        assertTrue(client.isServiceAccountsEnabled());
        assertFalse(client.isStandardFlowEnabled());
        assertFalse(client.isImplicitFlowEnabled());
        assertFalse(client.isDirectAccessGrantsEnabled());
        assertTrue(client.isFullScopeAllowed());
        assertEquals(SECRET, realm.clients().get(client.getId()).getSecret().getValue());
    }

    @Test
    void theServiceAccountHoldsRealmAdminOfItsRealm() {
        create();

        RealmResource realm = master.realm(realmName);
        String clientUuid = realm.clients().findByClientId(CLIENT_ID).get(0).getId();
        UserRepresentation serviceAccount = realm.clients().get(clientUuid).getServiceAccountUser();
        String realmManagement = realm.clients().findByClientId("realm-management").get(0).getId();

        Set<String> roles = realm.users().get(serviceAccount.getId()).roles().clientLevel(realmManagement).listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertEquals(Set.of("realm-admin"), roles);
    }

    // everything RealmProvisioner.create does is done as well: the owner, the defaults, the default catalog
    @Test
    void theRestOfTheCreationIsDoneLikeRealmProvisionerCreate() {
        CreateRealmResponse response = create();

        RealmResource realm = master.realm(realmName);
        assertEquals(realm.toRepresentation().getId(), response.id());
        assertEquals(1, realm.users().searchByEmail("owner@boot.test", true).size());
        assertEquals(response.ownerId(), realm.users().searchByEmail("owner@boot.test", true).get(0).getId());
        assertFalse(realm.clients().findByClientId(realmName).isEmpty(), "login client");
        assertFalse(realm.clients().findByClientId(OrgkeepApiClient.CLIENT_ID).isEmpty(), "API client");
        assertFalse(realm.clients().findByClientId("products").isEmpty(), "default catalog");
        Set<String> realmRoles = realm.roles().list().stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertTrue(realmRoles.contains("realm-owner"), realmRoles.toString());
    }

    @Test
    void anExistingRealmIsRefused() {
        create();

        assertThrows(RealmAlreadyExistsException.class, this::create);
    }

    @Test
    void wrongBootstrapCredentialsAreRefusedWithAMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> realmBootstrap.createWithBootstrapAdmin(
                        new CreateRealmRequest(realmName, "owner@boot.test", "s3cret"),
                        new AdminClient(CLIENT_ID, SECRET),
                        new BootstrapCredentials("admin", "not-the-password")));

        assertTrue(e.getMessage().contains("KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD"), e.getMessage());
        assertFalse(e.getMessage().contains("not-the-password"));
    }

    // the token is accepted but the user may not create realms: not the message of a refused password
    @Test
    void aMasterUserWithoutCreateRealmGetsItsOwnMessage() {
        String username = "no-realms-" + UUID.randomUUID().toString().substring(0, 8);
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue("pw-" + username);
        password.setTemporary(false);
        user.setCredentials(List.of(password));
        String userId;
        try (Response response = master.realm("master").users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> realmBootstrap.createWithBootstrapAdmin(
                            new CreateRealmRequest(realmName, "owner@boot.test", "s3cret"),
                            new AdminClient(CLIENT_ID, SECRET),
                            new BootstrapCredentials(username, "pw-" + username)));

            assertTrue(e.getMessage().contains("not allowed to create realms"), e.getMessage());
            assertFalse(e.getMessage().contains("pw-" + username));
        } finally {
            master.realm("master").users().get(userId).remove();
        }
    }

    @Test
    void theClientCredentialsTokenWorksAndReadsItsRealm() {
        create();

        try (Keycloak serviceAccount = asClient(realmName, CLIENT_ID, SECRET)) {
            assertEquals(realmName, serviceAccount.realm(realmName).toRepresentation().getRealm());
            // and administers it: the orgs, clients and users the core manages
            assertNotNull(serviceAccount.realm(realmName).clients().findAll());
        }
    }

    // forbidden cases

    @Test
    void aClientWithoutRealmManagementRolesGetsForbidden() {
        create();
        createServiceAccountClient("plain", "plain-secret", true);

        try (Keycloak plain = asClient(realmName, "plain", "plain-secret")) {
            WebApplicationException e = assertThrows(WebApplicationException.class,
                    () -> plain.realm(realmName).clients().findAll());
            assertEquals(403, e.getResponse().getStatus());
        }
    }

    // the roles of realm-admin are not in the token without the full scope: this is what the 403 message of the
    // startup tells to check
    @Test
    void withoutFullScopeEvenTheAdminClientGetsForbidden() {
        create();
        RealmResource realm = master.realm(realmName);
        ClientRepresentation client = realm.clients().findByClientId(CLIENT_ID).get(0);
        client.setFullScopeAllowed(false);
        realm.clients().get(client.getId()).update(client);

        try (Keycloak serviceAccount = asClient(realmName, CLIENT_ID, SECRET)) {
            WebApplicationException e = assertThrows(WebApplicationException.class,
                    () -> serviceAccount.realm(realmName).clients().findAll());
            assertEquals(403, e.getResponse().getStatus());
        }
    }

    @Test
    void theServiceAccountOfOneRealmCannotReachAnother() {
        create();
        String other = realmName + "-b";
        master.realms().create(realmRepresentation(other));
        try (Keycloak serviceAccount = asClient(realmName, CLIENT_ID, SECRET)) {
            WebApplicationException e = assertThrows(WebApplicationException.class,
                    () -> serviceAccount.realm(other).toRepresentation());
            assertTrue(e.getResponse().getStatus() == 403 || e.getResponse().getStatus() == 401,
                    "status " + e.getResponse().getStatus());
        } finally {
            master.realm(other).remove();
        }
    }

    private static org.keycloak.representations.idm.RealmRepresentation realmRepresentation(String name) {
        var realm = new org.keycloak.representations.idm.RealmRepresentation();
        realm.setRealm(name);
        realm.setEnabled(true);
        return realm;
    }

    private void createServiceAccountClient(String clientId, String secret, boolean serviceAccount) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(serviceAccount);
        client.setSecret(secret);
        master.realm(realmName).clients().create(client).close();
    }

    private Keycloak asClient(String realm, String clientId, String secret) {
        return KeycloakBuilder.builder()
                .serverUrl(adminAccess.serverUrl())
                .realm(realm)
                .clientId(clientId)
                .clientSecret(secret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }
}
