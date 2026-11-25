package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgOwnerService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgDetails;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.UpdateMemberStatusRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.web.CurrentRealm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
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
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mockito;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
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
 * The realm owner grants and revokes ownership of an organisation. REST tests check security and status codes; the
 * others call the service directly, to choose the caller precisely and to replay interleavings with spies. Real
 * logins check that org-owner (and therefore org-admin, a composite) really appears in, or disappears from, a
 * member's token: the login client already maps every role of the org client to its scope (see organisations.md),
 * whoever holds org-owner at the time of the login.
 * <p>
 * One realm for the whole class (@BeforeAll); the fixture (the org "acme", its owner olivia, and two plain members,
 * paul and claire) is rebuilt before every test (@BeforeEach) and torn down after it (@AfterEach).
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgOwnersTest {
    private static final String ORG = "acme";
    private static final String PASSWORD = "password";
    private static final String OWNERS_URL = "/api/admin/organisations/{org}/owners/{userId}";
    private static final String MEMBERS_URL = "/api/organisations/{org}/members/{userId}";
    private static final String ENABLED_URL = "/api/organisations/{org}/members/{userId}/enabled";

    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject OrgOwnerService ownerService;
    @Inject UserGroupService userGroupService;
    @Inject ServiceAccountService serviceAccountService;
    @InjectMock CurrentRealm currentRealm;
    // spies call the real repositories, unless a test says otherwise
    @InjectSpy KeycloakClients clients;
    @InjectSpy KeycloakGroups groups;
    @InjectSpy KeycloakOrgs orgs;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String ownerId;
    private String paulId;
    private String claireId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        realmProvisioner.ensureDefaults(testRealm.name());
        enablePasswordGrant();
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "olivia@acme.test", null, "Olivia", "Owner"));
        ownerId = userId("olivia@acme.test");
        paulId = member("paul");
        claireId = member("claire");
    }

    // Every org and user of the realm is deleted: the next test rebuilds the fixture from a clean realm.
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
            // a spy stub armed by a failed test (Quarkus resets @InjectSpy after this method, not before) or a lock
            // still held by a thread the test did not wait for would otherwise fail every following test
            log.warn("Cleanup failed, recreating the test realm", e);
            testRealm.close();
            testRealm = TestRealm.create();
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    // ---- grant

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void memberOfUsersBecomesOwner() {
        grantOverRest(paulId)
                .then().statusCode(200)
                .body("id", equalTo(paulId))
                .body("role", equalTo("ORG_OWNER"));

        assertFalse(isInUsers(paulId));
        OrgDetails details = orgService.get(testRealm.name(), ORG);
        assertTrue(details.owners().stream().anyMatch(owner -> owner.id().equals(paulId)));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void aPasswordGrantShowsOrgOwner() throws Exception {
        grantOverRest(paulId).then().statusCode(200);

        JsonNode claims = logIn("paul");
        assertTrue(strings(claims.at("/resource_access/org-" + ORG + "/roles")).contains("org-owner"));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void grantingTwiceIsIdempotent() {
        grantOverRest(paulId).then().statusCode(200);
        grantOverRest(paulId).then().statusCode(200).body("role", equalTo("ORG_OWNER"));
        assertFalse(isInUsers(paulId));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void severalOwnersAreSortedByUsername() {
        ownerService.grant(testRealm.name(), ORG, paulId);
        ownerService.grant(testRealm.name(), ORG, claireId);

        List<String> usernames = orgService.get(testRealm.name(), ORG).owners().stream()
                .map(OrgMember::username).toList();
        assertEquals(3, usernames.size());
        assertEquals(usernames.stream().sorted().toList(), usernames);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void memberInAdminAndATeamStaysInThem() {
        testRealm.joinGroup(paulId, "/" + ORG + "/user-groups/admin");
        userGroupService.create(testRealm.name(), ORG, new CreateUserGroupRequest("ops"));
        userGroupService.addMember(testRealm.name(), ORG, "ops", paulId);

        ownerService.grant(testRealm.name(), ORG, paulId);

        Set<String> paths = groupPaths(paulId);
        assertTrue(paths.contains("/" + ORG + "/user-groups/admin"));
        assertTrue(paths.contains("/" + ORG + "/user-groups/ops"));
    }

    // a failure between the role grant and leaving the group users: the member shows once (as an owner, the group
    // already skips him), and replaying the same call finishes the job
    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void failureBetweenTheTwoWritesIsResumedByReplaying() {
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(groups).leave(anyString(), eq(paulId), anyString());

        assertThrows(IllegalStateException.class, () -> ownerService.grant(testRealm.name(), ORG, paulId));
        assertTrue(isInUsers(paulId), "still in users: the role was granted but the group was not left yet");
        assertEquals(1, countMembers(paulId), "shown once, as an owner");

        ownerService.grant(testRealm.name(), ORG, paulId);
        assertFalse(isInUsers(paulId));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void memberDisabledByItsOrgAdminCannotBeMadeOwner() {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, ownerId);

        grantOverRest(paulId).then().statusCode(409)
                .body("message", equalTo("This member is disabled: enable them before making them an owner"));
        assertTrue(orgService.get(testRealm.name(), ORG).owners().stream().noneMatch(o -> o.id().equals(paulId)));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void privilegedMemberCannotBeMadeOwnerWithANeutralMessage() {
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(paulId, "realm-owner");

        grantOverRest(paulId).then().statusCode(409)
                .body("message", not(containsString("realm-owner")));
        assertTrue(orgService.get(testRealm.name(), ORG).owners().stream().noneMatch(o -> o.id().equals(paulId)));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void memberDisabledByTheOrgDisableIsNotRefused() {
        orgService.setEnabled(testRealm.name(), ORG, false);

        OrgMember owner = ownerService.grant(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.FALSE, owner.enabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
        assertTrue(orgService.get(testRealm.name(), ORG).owners().stream().anyMatch(o -> o.id().equals(paulId)));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void grantGets404() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest("globex", Set.of(), "gina@globex.test", null, "Gina", "Test"));
        String ginaId = userId("gina@globex.test");
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        String saUserId = serviceAccountUserId("batch");

        grantOverRest(ginaId).then().statusCode(404);
        grantOverRest(strangerId).then().statusCode(404);
        grantOverRest(saUserId).then().statusCode(404);
        grantOverRest("00000000-0000-0000-0000-000000000000").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void grantOnUnknownOrgGets404() {
        given().when().put(OWNERS_URL, "nope", paulId).then().statusCode(404);
    }

    // ---- revoke

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void ownerCreatedWithTheOrgLosesOwnership() {
        revokeOverRest(ownerId).then().statusCode(204);

        assertTrue(isInUsers(ownerId));
        assertEquals("ORG_USER", memberOf(ownerId).role().name());
        assertTrue(orgService.get(testRealm.name(), ORG).owners().isEmpty());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void revokeRemovesTheRoleFromTheNextToken() throws Exception {
        ownerService.grant(testRealm.name(), ORG, paulId);
        assertTrue(strings(logIn("paul").at("/resource_access/org-" + ORG + "/roles")).contains("org-owner"));

        revokeOverRest(paulId).then().statusCode(204);

        JsonNode after = logIn("paul");
        assertFalse(strings(after.at("/resource_access/org-" + ORG + "/roles")).contains("org-owner"));
        assertFalse(strings(after.at("/resource_access/org-" + ORG + "/roles")).contains("org-admin"));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void formerOwnerInAdminIsOrgAdminAfterRevoke() {
        testRealm.joinGroup(ownerId, "/" + ORG + "/user-groups/admin");

        revokeOverRest(ownerId).then().statusCode(204);

        assertEquals("ORG_ADMIN", memberOf(ownerId).role().name());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void afterRevokeTheMemberCanBeRemovedOrDisabled() {
        revokeOverRest(ownerId).then().statusCode(204);

        given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(false))
                .when().put(ENABLED_URL, ORG, ownerId).then().statusCode(200);
        assertFalse(testRealm.resource().users().get(ownerId).toRepresentation().isEnabled());

        given().when().delete(MEMBERS_URL, ORG, ownerId).then().statusCode(204);
        assertEquals(0, countMembers(ownerId));
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void revokeOnANonOwnerIsIdempotent() {
        revokeOverRest(paulId).then().statusCode(204);

        assertTrue(isInUsers(paulId));
        assertEquals("ORG_USER", memberOf(paulId).role().name());
    }

    // a failure between joining users and removing the role: the member shows once (as an owner), and replaying the
    // same call finishes the job
    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void revokeFailureBetweenTheTwoWritesIsResumedByReplaying() {
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(clients).revokeFromUser(anyString(), anyString(), eq(ownerId), anyString());

        assertThrows(IllegalStateException.class, () -> ownerService.revoke(testRealm.name(), ORG, ownerId));
        assertTrue(isInUsers(ownerId), "already joined users, the role was not removed yet");
        assertEquals(1, countMembers(ownerId), "shown once, as an owner");
        assertEquals("ORG_OWNER", memberOf(ownerId).role().name());

        ownerService.revoke(testRealm.name(), ORG, ownerId);
        assertEquals("ORG_USER", memberOf(ownerId).role().name());
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void revokeGets404() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest("globex", Set.of(), "gina@globex.test", null, "Gina", "Test"));
        String ginaId = userId("gina@globex.test");
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        String saUserId = serviceAccountUserId("batch");

        revokeOverRest(ginaId).then().statusCode(404);
        revokeOverRest(strangerId).then().statusCode(404);
        revokeOverRest(saUserId).then().statusCode(404);
        revokeOverRest("00000000-0000-0000-0000-000000000000").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void revokeOnUnknownOrgGets404() {
        given().when().delete(OWNERS_URL, "nope", paulId).then().statusCode(404);
    }

    // ---- forbidden cases (both endpoints)

    @Test
    void grantWithoutTokenGets401() {
        given().when().put(OWNERS_URL, ORG, paulId).then().statusCode(401);
    }

    @Test
    void revokeWithoutTokenGets401() {
        given().when().delete(OWNERS_URL, ORG, paulId).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void grantByAnOrgAdminOfThisOrgGets403() {
        grantOverRest(paulId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"org-acme:org-owner", "org-acme:org-admin"})
    void grantByAnOwnerOfThisOrgGets403() {
        grantOverRest(paulId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    void grantByAnOrgAdminOfAnotherOrgGets403() {
        grantOverRest(paulId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void grantByAPlainOrgUserGets403() {
        grantOverRest(paulId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void revokeByAnOrgAdminOfThisOrgGets403() {
        revokeOverRest(ownerId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"org-acme:org-owner", "org-acme:org-admin"})
    void revokeByAnOwnerOfThisOrgGets403() {
        revokeOverRest(ownerId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    void revokeByAnOrgAdminOfAnotherOrgGets403() {
        revokeOverRest(ownerId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void revokeByAPlainOrgUserGets403() {
        revokeOverRest(ownerId).then().statusCode(403);
    }

    // ---- unchanged behaviour (regression): remove and setEnabled still refuse an owner, for every caller

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void removeStillRefusesAnOwnerForAnOrgAdmin() {
        given().when().delete(MEMBERS_URL, ORG, ownerId).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    void removeStillRefusesAnOwnerForTheRealmOwnerToo() {
        given().when().delete(MEMBERS_URL, ORG, ownerId).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    @OidcSecurity(claims = @Claim(key = "sub", value = "alice-id"))
    void setEnabledStillRefusesAnOwnerForAnOrgAdmin() {
        given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(false))
                .when().put(ENABLED_URL, ORG, ownerId).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "boss", roles = "realm-owner")
    @OidcSecurity(claims = @Claim(key = "sub", value = "boss-id"))
    void setEnabledStillRefusesAnOwnerForTheRealmOwnerToo() {
        given().contentType(ContentType.JSON).body(new UpdateMemberStatusRequest(false))
                .when().put(ENABLED_URL, ORG, ownerId).then().statusCode(409);
    }

    // ---- concurrency

    // the disable of the org holds ORG exclusively: the grant waits max-wait, then gets the 409
    @Test
    void grantIsRefusedWhileTheOrgIsBeingDisabled() throws Exception {
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
            Future<?> disable = executor.submit(() -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> ownerService.grant(testRealm.name(), ORG, paulId));

            release.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // ---- helpers

    private Response grantOverRest(String userId) {
        return given().when().put(OWNERS_URL, ORG, userId);
    }

    private Response revokeOverRest(String userId) {
        return given().when().delete(OWNERS_URL, ORG, userId);
    }

    private boolean isInUsers(String userId) {
        return groupPaths(userId).contains("/" + ORG + "/user-groups/users");
    }

    private Set<String> groupPaths(String userId) {
        return testRealm.resource().users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .collect(Collectors.toSet());
    }

    private OrgMember memberOf(String userId) {
        return memberService.list(testRealm.name(), ORG, null, null, 0, 100).items().stream()
                .filter(member -> member.id().equals(userId))
                .findFirst().orElseThrow();
    }

    private long countMembers(String userId) {
        return memberService.list(testRealm.name(), ORG, null, null, 0, 100).items().stream()
                .filter(member -> member.id().equals(userId))
                .count();
    }

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }

    private String serviceAccountUserId(String serviceAccountName) {
        String clientUuid = clients.findUuid(testRealm.name(), OrgClients.serviceAccountClientId(ORG, serviceAccountName))
                .orElseThrow();
        return clients.serviceAccountUser(testRealm.name(), clientUuid).orElseThrow().getId();
    }

    /** The login client of the realm (clientId = realm name) accepts the password grant for the test. */
    private void enablePasswordGrant() {
        var clientsResource = testRealm.resource().clients();
        String uuid = clientsResource.findByClientId(testRealm.name()).get(0).getId();
        ClientRepresentation client = clientsResource.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clientsResource.get(uuid).update(client);
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

    private JsonNode logIn(String username) throws Exception {
        HttpResponse<String> response = post("/token", Map.of("grant_type", "password", "client_id", testRealm.name(),
                "username", username, "password", PASSWORD, "scope", "openid"));
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("access_token").asText();
        // header.payload.signature: the payload is base64url encoded JSON
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect" + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        } else if (!node.isMissingNode()) {
            values.add(node.asText());
        }
        return values;
    }
}
