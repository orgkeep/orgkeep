package org.orgkeep.components.organisation.member;

import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CatalogCredentials;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.UpdateMemberStatusRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import org.orgkeep.web.CurrentRealm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * An org admin disables and enables a member. The REST tests check the security and the status codes; the others call
 * the service, to be able to choose the caller and to replay interleavings with spies. Real logins: what matters is
 * that a disabled member cannot get a token any more.
 * <p>
 * The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}". @OidcSecurity gives the
 * principal the "sub" claim the resource reads as the caller.
 */
@QuarkusTest
class MemberStatusTest {
    private static final String ORG = "acme";
    private static final String PASSWORD = "password";
    private static final String URL = "/api/organisations/{org}/members/{userId}/enabled";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @InjectMock CurrentRealm currentRealm;
    // spies call the real repositories, unless a test says otherwise
    @InjectSpy KeycloakOrgs orgs;
    @InjectSpy KeycloakUsers users;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String ownerId;
    private String paulId;
    private CatalogCredentials prod;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        realmProvisioner.ensureDefaults(testRealm.name());
        enablePasswordGrant();
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "olivia@acme.test", null, "Olivia", "Owner"));
        ownerId = userId("olivia@acme.test");
        paulId = member("paul");
        givePaulAProductOfTheCatalog();
    }

    /**
     * A client may only introspect a token meant for it: Paul gets a product of the catalog prod through a team, so
     * that his token (scope prod) is for the client of the catalog, which stands for an API of the realm owner.
     */
    private void givePaulAProductOfTheCatalog() {
        String realm = testRealm.name();
        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        prod = catalogService.regenerateSecret(realm, "prod");
        productService.create(realm, "prod", new CreateProductRequest("shop", "Shop", null,
                List.of(new Feature("search", null)), List.of()));
        productService.assign(realm, ORG, "prod", "shop",
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("search"), Map.of()));
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("ops"));
        teamService.grantProduct(realm, ORG, "ops", "prod", "shop", new GrantProductRequest(null, 300L, null));
        userGroupService.addMember(realm, ORG, "ops", paulId);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // ---- REST: security and status codes

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void adminDisablesAndEnablesAMemberAndTheLoginFollows() throws Exception {
        JsonNode tokens = json.readTree(logIn("paul").body());
        String refreshToken = tokens.path("refresh_token").asText();
        String accessToken = tokens.path("access_token").asText();
        assertTrue(introspect(accessToken).path("active").asBoolean());

        setEnabledOverRest(paulId, false)
                .then().statusCode(200)
                .body("id", equalTo(paulId))
                .body("enabled", equalTo(false))
                .body("role", equalTo("ORG_USER"));

        assertEquals(400, logIn("paul").statusCode(), "Keycloak answers invalid_grant for a disabled user");
        assertEquals(400, refresh(refreshToken).statusCode(), "the session was closed");
        assertFalse(introspect(accessToken).path("active").asBoolean(), "an API that introspects sees it at once");

        setEnabledOverRest(paulId, true)
                .then().statusCode(200)
                .body("enabled", equalTo(true));
        assertEquals(200, logIn("paul").statusCode());
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void disablingTwiceIsIdempotent() {
        setEnabledOverRest(paulId, false).then().statusCode(200);
        setEnabledOverRest(paulId, false).then().statusCode(200).body("enabled", equalTo(false));
        assertFalse(user(paulId).isEnabled());
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void adminMayDisableAnotherAdmin() {
        String adamId = member("adam");
        testRealm.joinGroup(adamId, "/" + ORG + "/user-groups/admin");

        setEnabledOverRest(adamId, false)
                .then().statusCode(200)
                .body("role", equalTo("ORG_ADMIN"))
                .body("enabled", equalTo(false));
    }

    // whoever calls, an owner is refused: an org admin, another owner, the realm owner
    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void ownerCannotBeDisabledByAnAdmin() {
        setEnabledOverRest(ownerId, false).then().statusCode(409).body("message", containsString("owner"));
        assertTrue(user(ownerId).isEnabled());
    }

    @Test
    @TestSecurity(user = "bob", roles = {"org-acme:org-owner", "org-acme:org-admin"})
    @OidcSecurity(claims = @Claim(key = "sub", value = "bob-id"))
    void ownerCannotBeDisabledByAnotherOwner() {
        setEnabledOverRest(ownerId, false).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void ownerCannotBeDisabledByTheRealmOwner() {
        setEnabledOverRest(ownerId, false).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void privilegedMemberIsRefusedWithANeutralMessage() {
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(paulId, "realm-owner");

        setEnabledOverRest(paulId, false)
                .then().statusCode(409)
                .body("message", not(containsString("realm-owner")));
        assertTrue(user(paulId).isEnabled());
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void memberOfRealmManagementIsRefused() {
        testRealm.grantClientRole(paulId, "realm-management", "manage-users");

        setEnabledOverRest(paulId, false).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void userOfAnotherOrgOrUnknownGets404() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest("globex", Set.of(), "gina@globex.test", null, "Gina", "Test"));
        String ginaId = userId("gina@globex.test");

        setEnabledOverRest(ginaId, false).then().statusCode(404);
        setEnabledOverRest("00000000-0000-0000-0000-000000000000", false).then().statusCode(404);
        assertTrue(user(ginaId).isEnabled());
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-nope:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void unknownOrgGets404() {
        given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(false))
                .when().put(URL, "nope", paulId)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void missingBodyGets400() {
        given().contentType(ContentType.JSON)
                .when().put(URL, ORG, paulId)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    @OidcSecurity(claims = @Claim(key = "sub", value = "carol-id"))
    void simpleOrgUserGets403() {
        setEnabledOverRest(paulId, false).then().statusCode(403);
        assertTrue(user(paulId).isEnabled());
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "mallory-id"))
    void adminOfAnotherOrgGets403() {
        setEnabledOverRest(paulId, false).then().statusCode(403);
        assertTrue(user(paulId).isEnabled());
    }

    @Test
    void anonymousGets401() {
        given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(false))
                .when().put(URL, ORG, paulId)
                .then().statusCode(401);
    }

    // ---- service: order of the checks

    @Test
    void callerCannotChangeItsOwnStatus() {
        OperationNotAllowedException refused = assertThrows(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, paulId, false, paulId));
        assertTrue(refused.getMessage().contains("own status"));
        assertTrue(user(paulId).isEnabled());
    }

    // an owner who targets itself is told about itself, not about being an owner
    @Test
    void ownerTargetingItselfIsToldItIsItself() {
        OperationNotAllowedException refused = assertThrows(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, ownerId, false, ownerId));
        assertTrue(refused.getMessage().contains("own status"));
    }

    // not a member comes first: even for oneself, a user of no org learns nothing
    @Test
    void nonMemberIsReportedBeforeAnythingElse() {
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");

        assertThrows(MemberNotFoundException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, strangerId, false, strangerId));
    }

    // ---- interaction with the status of the org

    @Test
    void disabledOrgRefusesBothDirections() {
        orgService.setEnabled(testRealm.name(), ORG, false);

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id"));
        // paul is listed in the disabled members: enabling him alone would leave the org disabled with an active member
        assertThrowsExactly(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, paulId, true, "alice-id"));
        assertFalse(user(paulId).isEnabled());
    }

    // an enable that failed half way: the org is enabled, its list of disabled members is not empty yet
    @Test
    void enabledOrgWithDisabledMembersStillListedRefusesBothDirections() {
        String claireId = member("claire");
        orgService.setEnabled(testRealm.name(), ORG, false);
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), eq(claireId), eq(true));
        assertThrows(IllegalStateException.class, () -> orgService.setEnabled(testRealm.name(), ORG, true));
        assertTrue(orgService.get(testRealm.name(), ORG).enabled());

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id"));
        assertThrowsExactly(OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, claireId, true, "alice-id"));
    }

    @Test
    void memberDisabledByItsAdminStaysDisabledWhenTheOrgIsDisabledThenEnabled() throws Exception {
        String claireId = member("claire");
        memberService.setEnabled(testRealm.name(), ORG, claireId, false, "alice-id");

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertEquals(400, logIn("paul").statusCode());
        orgService.setEnabled(testRealm.name(), ORG, true);

        assertTrue(user(paulId).isEnabled(), "disabled with the org, enabled with it");
        assertFalse(user(claireId).isEnabled(), "disabled by its admin: stays so");
        assertEquals(200, logIn("paul").statusCode());
        assertEquals(400, logIn("claire").statusCode());
    }

    // ---- removing and adding

    @Test
    void removedDisabledMemberStaysDisabled() {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id");

        memberService.remove(testRealm.name(), ORG, paulId);

        assertFalse(user(paulId).isEnabled());
    }

    @Test
    void addingADisabledUserEnablesItAgain() throws Exception {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id");
        memberService.remove(testRealm.name(), ORG, paulId);

        OrgMember added = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test"));

        assertEquals(paulId, added.id());
        assertEquals(Boolean.TRUE, added.enabled());
        assertEquals(200, logIn("paul").statusCode());
    }

    @Test
    void addingADisabledUserToAnotherOrgEnablesItAgain() throws Exception {
        orgService.create(testRealm.name(), new CreateOrgRequest("globex", Set.of(), null, null));
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id");
        memberService.remove(testRealm.name(), ORG, paulId);

        OrgMember added = memberService.add(testRealm.name(), "globex", new AddMemberRequest("paul@acme.test", "Paul", "Test"));

        assertEquals(Boolean.TRUE, added.enabled());
        assertEquals(200, logIn("paul").statusCode());
    }

    @Test
    void disabledUserMadeOwnerOfANewOrgIsEnabledAgain() throws Exception {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id");
        memberService.remove(testRealm.name(), ORG, paulId);

        orgService.create(testRealm.name(),
                new CreateOrgRequest("beta", Set.of(), "paul@acme.test", null, "Paul", "Test"));

        assertTrue(user(paulId).isEnabled());
        assertEquals(200, logIn("paul").statusCode());
    }

    // ---- locks

    // the disable of the org holds ORG exclusively: the change of a member waits max-wait, then gets the 409
    @Test
    void memberChangeIsRefusedWhileTheOrgIsBeingDisabled() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    inside.countDown();
                    release.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).members(anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id"));

            release.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // a remove of the same member holds USER exclusively
    @Test
    void memberChangeIsRefusedWhileTheMemberIsBeingRemoved() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    inside.countDown();
                    release.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).removeMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> remove = inRequest(executor, () -> memberService.remove(testRealm.name(), ORG, paulId));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> memberService.setEnabled(testRealm.name(), ORG, paulId, false, "alice-id"));
            assertTrue(user(paulId).isEnabled());

            release.countDown();
            remove.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // ---- helpers

    private Response setEnabledOverRest(String userId, boolean enabled) {
        return given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(enabled))
                .when().put(URL, ORG, userId);
    }

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }

    private UserRepresentation user(String userId) {
        return testRealm.resource().users().get(userId).toRepresentation();
    }

    /** Runs the action on another thread inside a request context, like an HTTP request. */
    private static Future<?> inRequest(ExecutorService executor, Runnable action) {
        return executor.submit(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                action.run();
            } finally {
                requestContext.terminate();
            }
        });
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

    private HttpResponse<String> logIn(String username) throws Exception {
        return post("/token", Map.of("grant_type", "password", "client_id", testRealm.name(),
                "username", username, "password", PASSWORD, "scope", "openid prod"), null);
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return post("/token", Map.of("grant_type", "refresh_token", "client_id", testRealm.name(),
                "refresh_token", refreshToken), null);
    }

    /** The introspection endpoint, called by the client of the catalog, like an API of the realm owner. */
    private JsonNode introspect(String token) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (prod.clientId() + ":" + prod.clientSecret()).getBytes(StandardCharsets.UTF_8));
        return json.readTree(post("/token/introspect", Map.of("token", token), basic).body());
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String basicAuth) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect" + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (basicAuth != null) {
            request.header("Authorization", "Basic " + basicAuth);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
