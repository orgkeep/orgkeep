package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enabling and disabling an organisation, checked with real logins: Keycloak 26 lets the members of a disabled
 * organisation log in, so what matters is that their password grant fails.
 */
@QuarkusTest
class OrgStatusTest {
    private static final String ORG = "acme";
    private static final String PASSWORD = "password";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;
    private String paulId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        realmProvisioner.ensureDefaults(testRealm.name());
        enablePasswordGrant();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        paulId = member("paul");
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void disabledMemberCannotLogInUntilTheOrgIsEnabledAgain() throws Exception {
        assertEquals(200, logIn("paul"));

        assertFalse(orgService.setEnabled(testRealm.name(), ORG, false).enabled());
        assertEquals(400, logIn("paul"), "Keycloak answers invalid_grant for a disabled user");
        assertFalse(user(paulId).isEnabled());

        assertTrue(orgService.setEnabled(testRealm.name(), ORG, true).enabled());
        assertEquals(200, logIn("paul"));
    }

    // a user disabled for another reason (by the realm owner, in the console) is not enabled with the org
    @Test
    void memberDisabledBeforeStaysDisabled() {
        String claireId = member("claire");
        UserRepresentation claire = user(claireId);
        claire.setEnabled(false);
        testRealm.resource().users().get(claireId).update(claire);

        orgService.setEnabled(testRealm.name(), ORG, false);
        orgService.setEnabled(testRealm.name(), ORG, true);

        assertFalse(user(claireId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // A privileged user cannot join an org through Orgkeep, but may be a member anyway: added before the rule, in the
    // console, or granted the role afterwards (the case here). Disabling the org must not lock them out
    @Test
    void memberHoldingARealmRoleStaysEnabled() throws Exception {
        String ritaId = member("rita");
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(ritaId, "realm-owner");

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertTrue(user(ritaId).isEnabled());
        assertEquals(200, logIn("rita"));
        assertFalse(user(paulId).isEnabled());

        // not in the list of the members disabled with the org: enabling it does not touch them either
        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(ritaId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // an administrator of the realm in the console (a role of realm-management, no realm role) is privileged too
    @Test
    void memberWithARealmManagementRoleStaysEnabled() throws Exception {
        String adaId = member("ada");
        testRealm.grantClientRole(adaId, "realm-management", "manage-users");

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertTrue(user(adaId).isEnabled());
        assertEquals(200, logIn("ada"));
        assertFalse(user(paulId).isEnabled());
    }

    // only the realm roles of Orgkeep and realm-management count: a member with a realm role created in the console
    // is disabled with the org like any other
    @Test
    void memberWithAnotherRealmRoleIsDisabled() throws Exception {
        String frankId = member("frank");
        testRealm.createRealmRole("support");
        testRealm.grantRealmRole(frankId, "support");

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertFalse(user(frankId).isEnabled());
        assertEquals(400, logIn("frank"));

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(frankId).isEnabled());
    }

    // a second call changes nothing: disabling twice must not forget who was disabled by the first call
    @Test
    void isIdempotent() {
        orgService.setEnabled(testRealm.name(), ORG, false);
        orgService.setEnabled(testRealm.name(), ORG, false);
        orgService.setEnabled(testRealm.name(), ORG, true);
        orgService.setEnabled(testRealm.name(), ORG, true);

        assertTrue(user(paulId).isEnabled());
    }

    @Test
    void noMemberCanBeAddedToADisabledOrg() {
        orgService.setEnabled(testRealm.name(), ORG, false);

        assertThrowsExactly(OperationNotAllowedException.class, () -> memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("emma@acme.test", "Emma", "Roux")));
        // refused before the user is created
        assertTrue(testRealm.resource().users().searchByEmail("emma@acme.test", true).isEmpty());
    }

    private UserRepresentation user(String userId) {
        return testRealm.resource().users().get(userId).toRepresentation();
    }

    /** The login client of the realm (clientId = realm name) accepts the password grant for the test. */
    private void enablePasswordGrant() {
        var clients = testRealm.resource().clients();
        String uuid = clients.findByClientId(testRealm.name()).get(0).getId();
        ClientRepresentation client = clients.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clients.get(uuid).update(client);
    }

    /** A member who can log in by password grant right away: created with a password, then reused by the org. */
    private String member(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEmailVerified(true);
        user.setFirstName(username);
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        testRealm.createUser(user);
        return memberService.add(testRealm.name(), ORG, new AddMemberRequest(user.getEmail(), username, "Test")).id();
    }

    /** @return the HTTP status of a password grant on the login client */
    private int logIn(String username) throws Exception {
        String form = Map.of(
                        "grant_type", "password",
                        "client_id", testRealm.name(),
                        "username", username,
                        "password", PASSWORD)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }
}
