package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.components.organisation.member.MemberEligibility;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

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
    // Mockito spies on the real repositories, for this test class only: they call Keycloak as usual, and a test can
    // run something just before or after one call, or make it fail once, to replay a precise interleaving
    @InjectSpy KeycloakOrgs orgs;
    @InjectSpy KeycloakUsers users;
    @Inject OrgLocks orgLocks;

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

    // Keycloak pages the members of an org (10 by default): an org with more must have every member disabled,
    // not only the first page
    @Test
    void everyMemberIsDisabledBeyondTheFirstPage() {
        List<String> memberIds = new ArrayList<>(List.of(paulId));
        for (int i = 1; i <= 11; i++) {
            memberIds.add(memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("member" + i + "@acme.test", "Member" + i, "Test")).id());
        }

        orgService.setEnabled(testRealm.name(), ORG, false);
        List<String> stillEnabled = memberIds.stream().filter(userId -> user(userId).isEnabled()).toList();
        assertEquals(List.of(), stillEnabled);

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(memberIds.stream().allMatch(userId -> user(userId).isEnabled()));
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

    // The race of #23: the members are read, then someone is added before the org is saved disabled.
    // The spy runs the add right after the real read of the members. The org is marked disabled before that read,
    // so the add is refused (before the change, it went through and the new member stayed enabled)
    @Test
    void memberAddedAfterTheMembersAreReadIsRefused() {
        List<Exception> addFailures = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
                    Object members = invocation.callRealMethod();
                    try {
                        memberService.add(testRealm.name(), ORG, new AddMemberRequest("emma@acme.test", "Emma", "Roux"));
                    } catch (Exception e) {
                        addFailures.add(e);
                    }
                    return members;
                })
                .doCallRealMethod()
                .when(orgs).members(anyString(), anyString());

        orgService.setEnabled(testRealm.name(), ORG, false);

        assertEquals(1, addFailures.size());
        assertInstanceOf(OperationNotAllowedException.class, addFailures.getFirst());
        assertTrue(testRealm.resource().users().searchByEmail("emma@acme.test", true).isEmpty());
        assertFalse(user(paulId).isEnabled());
    }

    // The other side of the race: the add checked the org while it was still enabled, then the whole disable ran
    // before the membership was written (the spy disables the org just before the real addMember). The disable could
    // not see the new member: the add checks the org again once the user is a member, refuses and undoes the membership
    @Test
    void memberJoiningOnceTheOrgIsDisabledIsRefused() {
        Mockito.doAnswer(invocation -> {
                    orgService.setEnabled(testRealm.name(), ORG, false);
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        assertThrowsExactly(OperationNotAllowedException.class, () -> memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("emma@acme.test", "Emma", "Roux")));

        String emmaId = testRealm.resource().users().searchByEmail("emma@acme.test", true).getFirst().getId();
        assertEquals(List.of(), orgs.organisationsOf(testRealm.name(), emmaId), "the membership is undone");
        // untouched by the disable, which never saw her
        assertFalse(disabledMembers().contains(emmaId));
        assertTrue(user(emmaId).isEnabled());
        assertFalse(user(paulId).isEnabled());
    }

    // "Both sides win": the membership is written, then the whole disable runs (the spy disables the org just after
    // the real addMember) and counts Emma among the members it disables. The add then sees the org disabled, but with
    // Emma listed: the add came first, it goes through. Emma is a disabled member and comes back with the org
    @Test
    void memberDisabledWithTheOrgWhileBeingAddedStaysAMember() {
        Mockito.doAnswer(invocation -> {
                    Object added = invocation.callRealMethod();
                    orgService.setEnabled(testRealm.name(), ORG, false);
                    return added;
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        String emmaId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("emma@acme.test", "Emma", "Roux")).id();

        assertEquals(List.of(ORG), orgs.organisationsOf(testRealm.name(), emmaId));
        assertTrue(disabledMembers().contains(emmaId));
        assertFalse(user(emmaId).isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(emmaId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // The same for the owner of a new org: the disable counted Olga, the creation fails all the same and its rollback
    // deletes the org with its list of disabled members. Olga is enabled again first, otherwise nothing would
    @Test
    void ownerDisabledWithANewOrgIsEnabledAgainBeforeTheRollback() {
        Mockito.doAnswer(invocation -> {
                    Object added = invocation.callRealMethod();
                    orgService.setEnabled(testRealm.name(), "beta", false);
                    return added;
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        assertThrowsExactly(OperationNotAllowedException.class, () -> orgService.create(testRealm.name(),
                new CreateOrgRequest("beta", Set.of(), "olga@beta.test", null, "Olga", "Test")));

        assertTrue(orgs.findByName(testRealm.name(), "beta").isEmpty());
        String olgaId = testRealm.resource().users().searchByEmail("olga@beta.test", true).getFirst().getId();
        assertTrue(user(olgaId).isEnabled());
    }

    // An enable and a disable that overlap. The lock only covers one instance of the core: here the disable runs from
    // inside the enable, on the same thread, which the reentrant lock lets through, like a disable coming from another
    // instance. Interleaving: the enable marks the org enabled; the disable marks it disabled and finds Paul still
    // disabled, so lists nothing new; then the enable enables Paul. Before dropping the list, the enable reads the org
    // again, sees it disabled, and runs the disable steps again: Paul ends disabled and still listed
    @Test
    void disableRunningDuringAnEnableDisablesTheMembersAgain() {
        orgService.setEnabled(testRealm.name(), ORG, false);
        Mockito.doAnswer(invocation -> {
                    orgService.setEnabled(testRealm.name(), ORG, false);
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), eq(paulId), eq(true));

        orgService.setEnabled(testRealm.name(), ORG, true);

        assertFalse(orgService.get(testRealm.name(), ORG).enabled());
        assertFalse(user(paulId).isEnabled());
        assertTrue(disabledMembers().contains(paulId));

        // and the next enable brings him back
        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(paulId).isEnabled());
    }

    // Two calls at the same time, on two threads: the disable is held inside (the spy blocks it while it reads the
    // members) and the enable must wait for it. Instead of a sleep, the test waits until the enable is queued on the
    // lock of the org, then lets the disable finish: the enable runs after it, so the org ends enabled with Paul
    @Test
    void concurrentCallsForTheSameOrgRunOneAfterTheOther() throws Exception {
        CountDownLatch disableInside = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    disableInside.countDown();
                    releaseDisable.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).members(anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(disableInside.await(30, TimeUnit.SECONDS));

            Future<?> enable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, true));
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> orgLocks.lockOf(testRealm.name(), ORG).hasQueuedThreads());
            assertFalse(enable.isDone());

            releaseDisable.countDown();
            disable.get(30, TimeUnit.SECONDS);
            enable.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDisable.countDown();
            executor.shutdownNow();
        }

        assertTrue(orgService.get(testRealm.name(), ORG).enabled());
        assertTrue(user(paulId).isEnabled());
    }

    // The same for the owner of a new org, disabled while it is being created: the creation fails and is rolled back
    @Test
    void ownerJoiningANewOrgOnceItIsDisabledFailsTheCreation() {
        Mockito.doAnswer(invocation -> {
                    orgService.setEnabled(testRealm.name(), "beta", false);
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        assertThrowsExactly(OperationNotAllowedException.class, () -> orgService.create(testRealm.name(),
                new CreateOrgRequest("beta", Set.of(), "olga@beta.test", null, "Olga", "Test")));

        assertTrue(orgs.findByName(testRealm.name(), "beta").isEmpty());
    }

    // Keycloak fails while the members are being disabled (the spy throws for Claire once): the org is already
    // disabled, Claire is not. Calling again finishes the job instead of returning early because the org is disabled,
    // and every member disabled by the two calls is enabled again with the org
    @Test
    void disablingAgainFinishesAHalfDoneDisable() {
        String claireId = member("claire");
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), eq(claireId), eq(false));

        assertThrows(IllegalStateException.class, () -> orgService.setEnabled(testRealm.name(), ORG, false));
        assertFalse(orgService.get(testRealm.name(), ORG).enabled(), "the org is marked disabled first");
        assertTrue(user(claireId).isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertFalse(user(claireId).isEnabled());
        assertFalse(user(paulId).isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(claireId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // The same when enabling: the org is enabled first, a member left disabled is enabled by the next call, even
    // though the org is already enabled
    @Test
    void enablingAgainFinishesAHalfDoneEnable() {
        String claireId = member("claire");
        orgService.setEnabled(testRealm.name(), ORG, false);
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), eq(claireId), eq(true));

        assertThrows(IllegalStateException.class, () -> orgService.setEnabled(testRealm.name(), ORG, true));
        assertTrue(orgService.get(testRealm.name(), ORG).enabled(), "the org is marked enabled first");
        assertFalse(user(claireId).isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(claireId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    /**
     * Runs the action on another thread inside a request context, like an HTTP request: the Keycloak admin client is
     * a request scoped bean, the test thread has a context but a plain thread of an executor does not.
     */
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

    /** The ids listed in the attribute disabled-members of the org. */
    private List<String> disabledMembers() {
        return MemberEligibility.disabledMembers(orgs.getByName(testRealm.name(), ORG));
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
