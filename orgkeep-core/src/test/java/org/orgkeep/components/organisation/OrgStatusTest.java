package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgAlreadyExistsException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.InMemoryResourceLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusMock;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    @Inject ResourceLocks locks;

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
    // not see the new member: the add checks the org again once the user is a member, refuses and undoes the membership.
    // On one instance the lock refuses the disable while the add runs: the disable run here, on the
    // same thread, inside the add, stands for a disable from another instance, which does not share the locks. They
    // are replaced by locks that do nothing for this test (see installLocksOfAnotherInstance)
    @Test
    void memberJoiningOnceTheOrgIsDisabledIsRefused() {
        installLocksOfAnotherInstance();
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
    // Emma listed: the add came first, it goes through. Emma is a disabled member and comes back with the org.
    // Impossible on one instance (the lock refuses the disable while the add runs): this stands for a disable from
    // another instance, run here on the same thread, with locks that do nothing
    @Test
    void memberDisabledWithTheOrgWhileBeingAddedStaysAMember() {
        installLocksOfAnotherInstance();
        Mockito.doAnswer(invocation -> {
                    Object added = invocation.callRealMethod();
                    orgService.setEnabled(testRealm.name(), ORG, false);
                    return added;
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        OrgMember emma = memberService.add(testRealm.name(), ORG, new AddMemberRequest("emma@acme.test", "Emma", "Roux"));

        assertFalse(emma.enabled(), "the member returned is reported disabled");
        assertEquals(List.of(ORG), orgs.organisationsOf(testRealm.name(), emma.id()));
        assertTrue(disabledMembers().contains(emma.id()));
        assertFalse(user(emma.id()).isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(emma.id()).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // The same, but the disable fails right after writing its list (the spy makes the first user.setEnabled(false)
    // throw): Emma is listed, not disabled yet. The add trusts the list, not her state, and disables her itself: a
    // listed member is never left enabled in a disabled org
    @Test
    void memberListedByTheDisableIsDisabledByTheAdd() {
        installLocksOfAnotherInstance();
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), anyString(), eq(false));
        Mockito.doAnswer(invocation -> {
                    Object added = invocation.callRealMethod();
                    assertThrows(IllegalStateException.class,
                            () -> orgService.setEnabled(testRealm.name(), ORG, false));
                    return added;
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        OrgMember emma = memberService.add(testRealm.name(), ORG, new AddMemberRequest("emma@acme.test", "Emma", "Roux"));

        assertTrue(disabledMembers().contains(emma.id()));
        assertFalse(emma.enabled());
        assertFalse(user(emma.id()).isEnabled());
    }

    // With the locks, an add and a disable on one instance never overlap. On two threads: the add is held inside (the
    // spy blocks it just before the real addMember, ORG is locked shared), the disable is refused at once (a single
    // try, ORG exclusive) and nothing was written by it. The add ends normally: Emma is a plain member, not disabled
    @Test
    void disableIsRefusedWhileAnAddIsInProgress() throws Exception {
        CountDownLatch addInside = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    addInside.countDown();
                    releaseAdd.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> add = inRequest(executor, () -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("emma@acme.test", "Emma", "Roux")));
            assertTrue(addInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> orgService.setEnabled(testRealm.name(), ORG, false));

            releaseAdd.countDown();
            add.get(30, TimeUnit.SECONDS);
        } finally {
            releaseAdd.countDown();
            executor.shutdownNow();
        }

        String emmaId = testRealm.resource().users().searchByEmail("emma@acme.test", true).getFirst().getId();
        assertEquals(List.of(ORG), orgs.organisationsOf(testRealm.name(), emmaId));
        assertTrue(orgService.get(testRealm.name(), ORG).enabled(), "the refused disable changed nothing");
        assertEquals(List.of(), disabledMembers());
        assertTrue(user(emmaId).isEnabled());
        assertTrue(user(paulId).isEnabled());
    }

    // The add is the one refused when the disable comes first: the disable is held inside (the spy blocks it while it
    // reads the members), a member cannot be added meanwhile. The add waits up to max-wait (a short write), then
    // gets the 409, and no user was created for the refused add
    @Test
    void addIsRefusedWhileTheOrgIsBeingDisabled() throws Exception {
        CountDownLatch disableInside = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        blockOnMembersRead(disableInside, releaseDisable);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(disableInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("emma@acme.test", "Emma", "Roux")));
            assertTrue(testRealm.resource().users().searchByEmail("emma@acme.test", true).isEmpty());

            releaseDisable.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDisable.countDown();
            executor.shutdownNow();
        }
        assertFalse(user(paulId).isEnabled());
    }

    // #26: deleting the org while it is being disabled is refused. Without the lock, the disable went on disabling the
    // members it had already read after the deletion, with the list of the members to enable again deleted with the org
    @Test
    void deleteIsRefusedWhileTheOrgIsBeingDisabled() throws Exception {
        CountDownLatch disableInside = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        blockOnMembersRead(disableInside, releaseDisable);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(disableInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> orgService.delete(testRealm.name(), ORG));
            assertTrue(orgs.findByName(testRealm.name(), ORG).isPresent(), "the org is still there");

            releaseDisable.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDisable.countDown();
            executor.shutdownNow();
        }

        // nothing is left disabled outside of a list: Paul is disabled and listed, so the org can enable him again
        assertFalse(user(paulId).isEnabled());
        assertTrue(disabledMembers().contains(paulId));
        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(user(paulId).isEnabled());
    }

    // The inverse: a disable while the org is being deleted is refused (the deletion is held inside, the spy blocks it
    // just before the real delete of the org)
    @Test
    void disableIsRefusedWhileTheOrgIsBeingDeleted() throws Exception {
        CountDownLatch deleteInside = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    deleteInside.countDown();
                    releaseDelete.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).delete(anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> delete = inRequest(executor, () -> orgService.delete(testRealm.name(), ORG));
            assertTrue(deleteInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(user(paulId).isEnabled(), "the refused disable touched no member");

            releaseDelete.countDown();
            delete.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }
        assertTrue(orgs.findByName(testRealm.name(), ORG).isEmpty());
    }

    // #26, second case: a member added while the org is being deleted. The add is refused before any user is created
    // (it used to create the user, fail to join the org and keep the user)
    @Test
    void addIsRefusedWhileTheOrgIsBeingDeletedAndCreatesNoUser() throws Exception {
        CountDownLatch deleteInside = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    deleteInside.countDown();
                    releaseDelete.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).delete(anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> delete = inRequest(executor, () -> orgService.delete(testRealm.name(), ORG));
            assertTrue(deleteInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("emma@acme.test", "Emma", "Roux")));
            assertTrue(testRealm.resource().users().searchByEmail("emma@acme.test", true).isEmpty());

            releaseDelete.countDown();
            delete.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }
    }

    // remove picks its lock mode on a first read of the org (enabled, nothing listed: shared), then reads the org again
    // once locked. Here a whole disable runs between the two reads (the spy disables the org on another thread right
    // after the first read, and waits for it): the shared lock is no longer enough, remove releases it and takes the
    // exclusive one, then removes Claire. She is enabled again and leaves the list; Paul stays listed.
    // The lock really is exclusive when the org is written: a spy on removeMember (called under the lock) tries a
    // shared lock of the org from another thread, which must be refused. With the shared lock kept, it would succeed
    @Test
    void removeAfterADisableFinishedBetweenTheReadAndTheLockTakesTheExclusiveLock() throws Exception {
        String claireId = member("claire");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean orgHeldExclusively = new AtomicBoolean();
        try {
            Mockito.doAnswer(invocation -> {
                        orgHeldExclusively.set(isBusyForAnotherThread(executor,
                                LockRequest.org(testRealm.name(), ORG, LockMode.SHARED)));
                        return invocation.callRealMethod();
                    })
                    .doCallRealMethod()
                    .when(orgs).removeMember(anyString(), anyString(), eq(claireId));
            Mockito.doAnswer(invocation -> {
                        Object org = invocation.callRealMethod();
                        // only the first read of remove: the next ones call the real method
                        Future<?> disable = inRequest(executor,
                                () -> orgService.setEnabled(testRealm.name(), ORG, false));
                        disable.get(60, TimeUnit.SECONDS);
                        return org;
                    })
                    .doCallRealMethod()
                    .when(orgs).getByName(anyString(), eq(ORG));

            memberService.remove(testRealm.name(), ORG, claireId);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(orgHeldExclusively.get(), "the org was locked exclusively while the member was removed");
        assertTrue(user(claireId).isEnabled(), "disabled by the disable, enabled again by the remove");
        assertFalse(disabledMembers().contains(claireId));
        assertTrue(disabledMembers().contains(paulId), "the other members stay listed");
        assertFalse(user(paulId).isEnabled());
        assertEquals(List.of(), orgs.organisationsOf(testRealm.name(), claireId));
    }

    // enabled org, nothing listed: remove only needs the shared lock, so it goes along with an add in progress (two
    // short writes in the same org do not bother each other)
    @Test
    void removeFromAnEnabledOrgGoesAlongWithAnAddInProgress() throws Exception {
        String claireId = member("claire");
        CountDownLatch addInside = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    addInside.countDown();
                    releaseAdd.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> add = inRequest(executor, () -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("emma@acme.test", "Emma", "Roux")));
            assertTrue(addInside.await(30, TimeUnit.SECONDS));

            memberService.remove(testRealm.name(), ORG, claireId);
            assertEquals(List.of(), orgs.organisationsOf(testRealm.name(), claireId));

            releaseAdd.countDown();
            add.get(30, TimeUnit.SECONDS);
        } finally {
            releaseAdd.countDown();
            executor.shutdownNow();
        }
    }

    // A member disabled with the org and then removed from it leaves the list, enabled again: she belongs to no org,
    // and enabling the org would not touch her any more
    @Test
    void removedMemberLeavesTheDisabledMembers() {
        String claireId = member("claire");
        orgService.setEnabled(testRealm.name(), ORG, false);
        assertTrue(disabledMembers().contains(claireId));

        memberService.remove(testRealm.name(), ORG, claireId);

        assertFalse(disabledMembers().contains(claireId));
        assertTrue(disabledMembers().contains(paulId), "the other members stay listed");
        assertTrue(user(claireId).isEnabled());
        assertFalse(user(paulId).isEnabled());
    }

    // The same for the owner of a new org: the disable counted Olga, the creation fails all the same and its rollback
    // deletes the org with its list of disabled members. Olga is enabled again first, otherwise nothing would.
    // Like above, a disable from another instance, replayed on the same thread
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
    // members, ORG is locked exclusively) and the enable is refused at once, without waiting (a single try). The
    // disable then finishes alone: the org ends disabled, Paul disabled and listed
    @Test
    void secondCallForTheSameOrgIsRefusedAtOnce() throws Exception {
        CountDownLatch disableInside = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        blockOnMembersRead(disableInside, releaseDisable);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disable = inRequest(executor, () -> orgService.setEnabled(testRealm.name(), ORG, false));
            assertTrue(disableInside.await(30, TimeUnit.SECONDS));

            long start = System.nanoTime();
            ResourceBusyException busy = assertThrowsExactly(ResourceBusyException.class,
                    () -> orgService.setEnabled(testRealm.name(), ORG, true));
            assertEquals("A change is in progress, try again later", busy.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0,
                    "refused without waiting");
            assertFalse(disable.isDone());

            releaseDisable.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDisable.countDown();
            executor.shutdownNow();
        }

        assertFalse(orgService.get(testRealm.name(), ORG).enabled());
        assertFalse(user(paulId).isEnabled());
        assertTrue(disabledMembers().contains(paulId));
    }

    // A creation waits a little for a change in progress on an org of the same name (a short write): held for less
    // than max-wait (2 s by default), the second creation goes on and finds the org: "already exists"; held for
    // longer, it is refused with the "change in progress" 409
    @Test
    void creationWaitsAShortTimeForAChangeInProgressOnTheSameName() throws Exception {
        CountDownLatch creationInside = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    creationInside.countDown();
                    releaseCreation.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).create(anyString(), any());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = inRequest(executor, () -> orgService.create(testRealm.name(),
                    new CreateOrgRequest("beta", Set.of(), null, null)));
            assertTrue(creationInside.await(30, TimeUnit.SECONDS));

            // held longer than max-wait: refused
            assertThrowsExactly(ResourceBusyException.class, () -> orgService.create(testRealm.name(),
                    new CreateOrgRequest("beta", Set.of(), null, null)));

            // released while the second creation is waiting: it gets the lock, then "already exists"
            Future<?> second = inRequest(executor, () -> orgService.create(testRealm.name(),
                    new CreateOrgRequest("beta", Set.of(), null, null)));
            Thread.sleep(300);
            releaseCreation.countDown();
            first.get(30, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS));
            assertInstanceOf(OrgAlreadyExistsException.class, failure.getCause());
        } finally {
            releaseCreation.countDown();
            executor.shutdownNow();
        }
    }

    // The same for the owner of a new org, disabled while it is being created: the creation fails and is rolled back.
    // The "disable entirely before the owner joins" order of the lock, replayed on the same thread
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

    /** Whether another thread is refused this lock right now (a single try, released at once if it is granted). */
    private boolean isBusyForAnotherThread(ExecutorService executor, LockRequest request) throws Exception {
        return executor.submit(() -> {
            try (HeldLocks ignored = locks.tryAcquire(request)) {
                return false;
            } catch (ResourceBusyException e) {
                return true;
            }
        }).get(30, TimeUnit.SECONDS);
    }

    /** The spy blocks the call that reads the members of the org (a disable in progress) until the test lets it go. */
    private void blockOnMembersRead(CountDownLatch inside, CountDownLatch release) {
        Mockito.doAnswer(invocation -> {
                    inside.countDown();
                    release.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).members(anyString(), anyString());
    }

    /**
     * Replaces the locks by locks that do nothing, for the current test only (QuarkusMock): the locks of another
     * instance of the core, which does not share them. The tests that replay the interleavings of an add and a disable
     * from another instance run the disable inside the add, on the same thread: with the real locks that is a shared
     * lock turned exclusive, which is refused as a programming error.
     */
    private static void installLocksOfAnotherInstance() {
        // QuarkusMock needs a mock of the class of the bean (InMemoryResourceLocks, the only ResourceLocks), not of
        // the interface: every acquire and tryAcquire answers a HeldLocks that holds nothing
        InMemoryResourceLocks noLocks = Mockito.mock(InMemoryResourceLocks.class,
                invocation -> invocation.getMethod().getReturnType() == HeldLocks.class
                        ? NOTHING_HELD : Mockito.RETURNS_DEFAULTS.answer(invocation));
        QuarkusMock.installMockForType(noLocks, InMemoryResourceLocks.class);
    }

    private static final HeldLocks NOTHING_HELD = new HeldLocks() {
        @Override
        public HeldLocks add(LockRequest... requests) {
            return this;
        }

        @Override
        public void close() {
        }
    };

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
        return DisabledMembers.of(orgs.getByName(testRealm.name(), ORG));
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
