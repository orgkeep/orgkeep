package org.orgkeep.components.organisation.suspension;

import org.orgkeep.api.AccountSuspensionService;
import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CatalogCredentials;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.SuspendRequest;
import org.orgkeep.api.model.Suspensions;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.web.CurrentRealm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mockito;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security and status codes of the four realm owner endpoints, over REST. The order of the checks and the edge
 * cases of the suspend / lift logic itself are covered by {@link AccountSuspensionServiceTest} and
 * {@link SuspensionLifecycleTest}, closer to the service and easier to set up with spies.
 * <p>
 * One realm for the whole class (@BeforeAll); the fixture (the org "acme", a member paul, a service account
 * "batch") is rebuilt before every test (@BeforeEach) and torn down after it (@AfterEach).
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspensionsResourceTest {
    private static final String ORG = "acme";
    private static final String MEMBER_SUSPENSION_URL = "/api/admin/organisations/{org}/members/{userId}/suspension";
    private static final String SA_SUSPENSION_URL = "/api/admin/organisations/{org}/service-accounts/{sa}/suspension";
    private static final String PASSWORD = "token-test-password";

    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject AccountSuspensionService suspensionService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @InjectMock CurrentRealm currentRealm;
    @Inject KeycloakClients clients;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String ownerId;
    private String paulId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        realmProvisioner.ensureDefaults(testRealm.name());
        testRealm.declareSuspensionAttributes();
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "olivia@acme.test", null, "Olivia", "Owner"));
        ownerId = userId("olivia@acme.test");
        paulId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String org : List.of(ORG, "globex")) {
                try {
                    orgService.delete(testRealm.name(), org);
                } catch (OrgNotFoundException ignored) {
                    // not created by this test
                }
            }
            testRealm.resource().users().list(0, 1000)
                    .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
        } catch (RuntimeException e) {
            log.warn("Cleanup failed, recreating the test realm", e);
            testRealm.close();
            testRealm = TestRealm.create();
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    // ---- behaviour, realm owner

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void realmOwnerSuspendsAMemberAndLiftsIt() {
        suspendMemberOverRest(paulId, "Breached contract")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("suspension.reason", equalTo("Breached contract"))
                .body("suspension.suspendedBy", equalTo("boss-id"));
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());

        liftMemberOverRest(paulId)
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("suspension", nullValue());
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void realmOwnerSuspendsAnOwner() {
        suspendMemberOverRest(ownerId, "Breached contract").then().statusCode(200).body("enabled", equalTo(false));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void realmOwnerSuspendsAndLiftsAServiceAccount() {
        suspendServiceAccountOverRest("batch", "Abusive usage")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("suspension.reason", equalTo("Abusive usage"));

        liftServiceAccountOverRest("batch")
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("suspension", nullValue());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void privilegedMemberIsRefusedWithANeutralMessage() {
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(paulId, "realm-owner");

        suspendMemberOverRest(paulId, "Some reason")
                .then().statusCode(409)
                .body("message", not(containsString("realm-owner")));
    }

    // ---- 400 / 404

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void blankReasonGets400() {
        suspendMemberOverRest(paulId, "").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void tooLongReasonGets400() {
        suspendMemberOverRest(paulId, "x".repeat(501)).then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void unknownOrgGets404() {
        given().contentType(ContentType.JSON).body(new SuspendRequest("reason"))
                .when().put(MEMBER_SUSPENSION_URL, "nope", paulId).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void unknownUserGets404() {
        suspendMemberOverRest("00000000-0000-0000-0000-000000000000", "reason").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void memberOfAnotherOrgGets404() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest("globex", Set.of(), "gina@globex.test", null, "Gina", "Test"));
        String ginaId = userId("gina@globex.test");

        suspendMemberOverRest(ginaId, "reason").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void serviceAccountUserOnTheMembersPathGets404() {
        String saUserId = serviceAccountUserId("batch");

        suspendMemberOverRest(saUserId, "reason").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void unknownServiceAccountGets404() {
        suspendServiceAccountOverRest("nope", "reason").then().statusCode(404);
    }

    // ---- 401

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "email", value = "boss@orgkeep.test"))
    void suspendWithoutSubjectGets401() {
        suspendMemberOverRest(paulId, "reason").then().statusCode(401);
        suspendServiceAccountOverRest("batch", "reason").then().statusCode(401);
    }

    @Test
    void anonymousGets401OnEveryEndpoint() {
        given().when().put(MEMBER_SUSPENSION_URL, ORG, paulId).then().statusCode(401);
        given().when().delete(MEMBER_SUSPENSION_URL, ORG, paulId).then().statusCode(401);
        given().when().put(SA_SUSPENSION_URL, ORG, "batch").then().statusCode(401);
        given().when().delete(SA_SUSPENSION_URL, ORG, "batch").then().statusCode(401);
    }

    // ---- 403

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnEveryEndpoint() {
        suspendMemberOverRest(paulId, "reason").then().statusCode(403);
        liftMemberOverRest(paulId).then().statusCode(403);
        suspendServiceAccountOverRest("batch", "reason").then().statusCode(403);
        liftServiceAccountOverRest("batch").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"org-acme:org-owner", "org-acme:org-admin"})
    void orgOwnerGets403() {
        suspendMemberOverRest(paulId, "reason").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void plainOrgUserGets403() {
        suspendMemberOverRest(paulId, "reason").then().statusCode(403);
    }

    // ---- org admins never see suspendedBy

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void realmOwnerSeesSuspendedByOnTheMembersListToo() {
        suspendMemberOverRest(paulId, "Breached contract").then().statusCode(200);

        given().when().get("/api/organisations/{org}/members", ORG)
                .then().statusCode(200)
                .body("items.find { it.id == '" + paulId + "' }.suspension.suspendedBy", equalTo("boss-id"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminSeesTheReasonButNotWhoSuspended() {
        suspendAsRealmOwner(paulId, "Breached contract");

        given().when().get("/api/organisations/{org}/members", ORG)
                .then().statusCode(200)
                .body("items.find { it.id == '" + paulId + "' }.suspension.reason", equalTo("Breached contract"))
                .body("items.find { it.id == '" + paulId + "' }.suspension.suspendedBy", nullValue());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void realmOwnerSeesSuspendedByOnServiceAccountsListAndGetToo() {
        suspendServiceAccountOverRest("batch", "Abusive usage").then().statusCode(200);

        given().when().get("/api/organisations/{org}/service-accounts", ORG)
                .then().statusCode(200)
                .body("find { it.name == 'batch' }.suspension.suspendedBy", equalTo("boss-id"));
        given().when().get("/api/organisations/{org}/service-accounts/{sa}", ORG, "batch")
                .then().statusCode(200)
                .body("suspension.suspendedBy", equalTo("boss-id"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminSeesTheReasonButNotWhoSuspendedOnServiceAccounts() {
        suspensionService.suspendServiceAccount(testRealm.name(), ORG, "batch", "Abusive usage", "boss-id");

        given().when().get("/api/organisations/{org}/service-accounts", ORG)
                .then().statusCode(200)
                .body("find { it.name == 'batch' }.suspension.reason", equalTo("Abusive usage"))
                .body("find { it.name == 'batch' }.suspension.suspendedBy", nullValue());
        given().when().get("/api/organisations/{org}/service-accounts/{sa}", ORG, "batch")
                .then().statusCode(200)
                .body("suspension.reason", equalTo("Abusive usage"))
                .body("suspension.suspendedBy", nullValue());
    }

    /** Suspends directly through the service (the realm owner's own REST call is tested above): sets up the fixture for a test running as another caller. */
    private void suspendAsRealmOwner(String userId, String reason) {
        suspensionService.suspendMember(testRealm.name(), ORG, userId, reason, "boss-id");
    }

    // ---- effect of a suspension: refused logins, an earlier token revoked for introspection

    // the catalog role grant puts prod in the audience of the token (CatalogCredentials): the same mechanism an API
    // of the realm owner relies on to introspect it
    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void suspendingAMemberRevokesLoginAndIntrospection() throws Exception {
        CatalogCredentials prod = setUpIntrospectionFixture();
        try {
            catalogRoleService.grant(testRealm.name(), "prod", "searcher", paulId);
            setPassword(paulId);
            String accessToken = json.readTree(passwordGrant("paul@acme.test", "openid prod").body())
                    .path("access_token").asText();
            assertTrue(introspect(prod, accessToken).path("active").asBoolean());

            suspendMemberOverRest(paulId, "Breached contract").then().statusCode(200);

            assertEquals(400, passwordGrant("paul@acme.test", "openid").statusCode(),
                    "password grant refused while suspended");
            assertFalse(introspect(prod, accessToken).path("active").asBoolean(),
                    "token issued before the suspension revoked for introspection");

            liftMemberOverRest(paulId).then().statusCode(200);
        } finally {
            tearDownIntrospectionFixture();
        }
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void suspendingAServiceAccountRevokesClientCredentials() throws Exception {
        CatalogCredentials prod = setUpIntrospectionFixture();
        try {
            // created after the catalog: a service account's own client only gets a new catalog as an optional
            // scope at its creation, unlike the login client (patched up by CatalogSetup.ensure)
            serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("intro-sa", null, null));
            String saUserId = serviceAccountUserId("intro-sa");
            catalogRoleService.grant(testRealm.name(), "prod", "searcher", saUserId);
            String clientId = OrgClients.serviceAccountClientId(ORG, "intro-sa");
            String secret = serviceAccountService.regenerateSecret(testRealm.name(), ORG, "intro-sa").clientSecret();

            Grant granted = clientCredentialsGrant(clientId, secret, "prod");
            assertEquals(200, granted.status(), granted.body());
            String accessToken = granted.accessToken();
            assertTrue(introspect(prod, accessToken).path("active").asBoolean());

            suspendServiceAccountOverRest("intro-sa", "Abusive usage").then().statusCode(200);

            Grant refused = clientCredentialsGrant(clientId, secret, "prod");
            assertEquals(401, refused.status(), "client credentials grant refused while suspended");
            assertFalse(introspect(prod, accessToken).path("active").asBoolean(),
                    "token issued before the suspension revoked for introspection");

            liftServiceAccountOverRest("intro-sa").then().statusCode(200);
        } finally {
            tearDownIntrospectionFixture();
        }
    }

    // ---- no leak: the attributes exist and the account is still enabled, the one state where a mapper could
    // actually leak them into a token, unlike the two tests above (issued before the attributes existed) or an
    // introspection of a revoked token (which only ever answers "active: false")

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void suspensionAttributesNeverLeakIntoATokenUserinfoOrIntrospection() throws Exception {
        CatalogCredentials prod = setUpIntrospectionFixture();
        try {
            catalogRoleService.grant(testRealm.name(), "prod", "searcher", paulId);
            setPassword(paulId);
            // set directly, bypassing the suspension service (which would disable the account and end its
            // sessions): the account stays enabled and can still authenticate, attributes and all
            setRawSuspensionAttributes(paulId);

            JsonNode tokens = json.readTree(passwordGrant("paul@acme.test", "openid prod").body());
            String accessToken = tokens.path("access_token").asText();
            String idToken = tokens.path("id_token").asText();
            JsonNode userinfo = json.readTree(get("/protocol/openid-connect/userinfo", accessToken).body());
            JsonNode introspection = introspect(prod, accessToken);

            assertTrue(introspection.path("active").asBoolean(), "the account is still enabled");
            assertNoSuspensionAttributes(decode(accessToken));
            assertNoSuspensionAttributes(decode(idToken));
            assertNoSuspensionAttributes(userinfo);
            assertNoSuspensionAttributes(introspection);
        } finally {
            tearDownIntrospectionFixture();
        }
    }

    // the record is set directly on an enabled user (not suspended through the service, which would log them out):
    // the point is the declaration in the user profile, admin-only regardless of whether a suspension is in progress
    @Test
    void selfEditOfTheSuspensionReasonIsRefusedAndChangesNothing() throws Exception {
        String userId = createQuentin();
        setRawSuspensionReason(userId, "set-directly-by-the-admin");

        String accessToken = json.readTree(passwordGrant("quentin@acme.test", "openid").body())
                .path("access_token").asText();

        HttpResponse<String> selfEdit = setAccountAttribute(accessToken, "suspension-reason", "quentin-set-this-himself");
        assertEquals(400, selfEdit.statusCode());
        assertEquals("set-directly-by-the-admin", rawSuspensionReason(userId), "self-edit changed nothing");
    }

    // ---- helpers

    private Response suspendMemberOverRest(String userId, String reason) {
        return given().contentType(ContentType.JSON).body(new SuspendRequest(reason))
                .when().put(MEMBER_SUSPENSION_URL, ORG, userId);
    }

    private Response liftMemberOverRest(String userId) {
        return given().when().delete(MEMBER_SUSPENSION_URL, ORG, userId);
    }

    private Response suspendServiceAccountOverRest(String sa, String reason) {
        return given().contentType(ContentType.JSON).body(new SuspendRequest(reason))
                .when().put(SA_SUSPENSION_URL, ORG, sa);
    }

    private Response liftServiceAccountOverRest(String sa) {
        return given().when().delete(SA_SUSPENSION_URL, ORG, sa);
    }

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }

    private String serviceAccountUserId(String serviceAccountName) {
        String clientUuid = clients.findUuid(testRealm.name(), OrgClients.serviceAccountClientId(ORG, serviceAccountName))
                .orElseThrow();
        return clients.serviceAccountUser(testRealm.name(), clientUuid).orElseThrow().getId();
    }

    // ---- token helpers (password / client credentials grants, introspection, userinfo, Account API)

    /** The login client of the realm (clientId = realm name) accepts the password grant, for these tests only. */
    private void enablePasswordGrant() {
        var clientsResource = testRealm.resource().clients();
        String uuid = clientsResource.findByClientId(testRealm.name()).get(0).getId();
        ClientRepresentation client = clientsResource.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clientsResource.get(uuid).update(client);
    }

    /** A real password, and the pending UPDATE_PASSWORD required action (set by MemberMapper on a new member)
     * cleared, as if the member had followed the invitation link. */
    private void setPassword(String userId) {
        enablePasswordGrant();
        var userResource = testRealm.resource().users().get(userId);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        userResource.resetPassword(credential);
        UserRepresentation user = userResource.toRepresentation();
        user.setRequiredActions(List.of());
        user.setEmailVerified(true);
        userResource.update(user);
    }

    /** A user created directly (no pending required action), used only for the self-edit test, unrelated to any org. */
    private String createQuentin() {
        enablePasswordGrant();
        UserRepresentation user = new UserRepresentation();
        user.setUsername("quentin@acme.test");
        user.setEmail("quentin@acme.test");
        user.setEmailVerified(true);
        user.setFirstName("Quentin");
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        return testRealm.createUser(user);
    }

    /**
     * A catalog and a role, grantable directly: granting it to a user puts the catalog client in the audience of
     * the user's token (CatalogCredentials), what an API of the realm owner needs to introspect it. A client
     * created before the catalog (the login client, shared by every member) is patched up by CatalogSetup.ensure;
     * a service account's own client is not, so it must be created after the catalog exists.
     */
    private CatalogCredentials setUpIntrospectionFixture() {
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");
        catalogRoleService.create(testRealm.name(), "prod", new CreateCatalogRoleRequest("searcher", null, null, true));
        return prod;
    }

    /** Deleting the role first revokes it from the user too: the catalog is then free of roles and can be deleted. */
    private void tearDownIntrospectionFixture() {
        catalogRoleService.delete(testRealm.name(), "prod", "searcher");
        catalogService.delete(testRealm.name(), "prod");
    }

    private void assertNoSuspensionAttributes(JsonNode node) {
        String text = node.toString();
        for (String name : List.of("suspension-reason", "suspended-at", "suspended-by", "suspension-org-choice")) {
            assertFalse(text.contains(name), "leaked '" + name + "' in " + text);
        }
    }

    private String rawSuspensionReason(String userId) {
        Map<String, List<String>> attributes = testRealm.resource().users().get(userId).toRepresentation().getAttributes();
        return attributes == null ? null : attributes.getOrDefault("suspension-reason", List.of()).stream().findFirst().orElse(null);
    }

    /** Sets the attribute directly through the admin API, bypassing the suspension service (used only to check that
     * the declaration in the user profile blocks a self-edit, independent of the suspension logic itself). */
    private void setRawSuspensionReason(String userId, String value) {
        var userResource = testRealm.resource().users().get(userId);
        UserRepresentation user = userResource.toRepresentation();
        Map<String, List<String>> attributes = user.getAttributes() == null ? new HashMap<>() : new HashMap<>(user.getAttributes());
        attributes.put("suspension-reason", List.of(value));
        user.setAttributes(attributes);
        userResource.update(user);
    }

    /** Sets all four suspension attributes directly through the admin API, bypassing the suspension service, on an
     * account left enabled: the only state where a mapper could actually leak them into a token (the attributes
     * exist and the user can still authenticate). */
    private void setRawSuspensionAttributes(String userId) {
        var userResource = testRealm.resource().users().get(userId);
        UserRepresentation user = userResource.toRepresentation();
        Map<String, List<String>> attributes = user.getAttributes() == null ? new HashMap<>() : new HashMap<>(user.getAttributes());
        attributes.put(Suspensions.REASON, List.of("Breached contract"));
        attributes.put(Suspensions.SUSPENDED_AT, List.of(String.valueOf(Instant.now().getEpochSecond())));
        attributes.put(Suspensions.SUSPENDED_BY, List.of("boss-id"));
        attributes.put(Suspensions.ORG_CHOICE, List.of("enabled"));
        user.setAttributes(attributes);
        userResource.update(user);
    }

    private HttpResponse<String> passwordGrant(String username, String scope) throws Exception {
        return post("/protocol/openid-connect/token", Map.of("grant_type", "password", "client_id", testRealm.name(),
                "username", username, "password", PASSWORD, "scope", scope), null);
    }

    private record Grant(int status, String body) {
        String accessToken() throws Exception {
            return new ObjectMapper().readTree(body).path("access_token").asText();
        }
    }

    private Grant clientCredentialsGrant(String clientId, String secret, String scope) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = post("/protocol/openid-connect/token",
                Map.of("grant_type", "client_credentials", "scope", scope), "Basic " + basic);
        return new Grant(response.statusCode(), response.body());
    }

    /** The catalog client introspects a token it is the audience of (RFC 7662), like an API of the realm owner. */
    private JsonNode introspect(CatalogCredentials credentials, String token) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (credentials.clientId() + ":" + credentials.clientSecret()).getBytes(StandardCharsets.UTF_8));
        return json.readTree(
                post("/protocol/openid-connect/token/introspect", Map.of("token", token), "Basic " + basic).body());
    }

    private JsonNode decode(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(keycloakUrl + "/realms/" + testRealm.name() + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return send(request);
    }

    /** The Account REST API negotiates content: without "Accept: application/json" it serves the Account Console SPA. */
    private HttpResponse<String> setAccountAttribute(String bearerToken, String name, String value) throws Exception {
        HttpResponse<String> current = get("/account", bearerToken);
        ObjectNode body = (ObjectNode) json.readTree(current.body().isBlank() ? "{}" : current.body());
        ObjectNode attributes = body.has("attributes") && body.get("attributes").isObject()
                ? (ObjectNode) body.get("attributes") : json.createObjectNode();
        attributes.putArray(name).add(value);
        body.set("attributes", attributes);
        HttpRequest request = HttpRequest.newBuilder(URI.create(keycloakUrl + "/realms/" + testRealm.name() + "/account"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String authorization) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(keycloakUrl + "/realms/" + testRealm.name() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
