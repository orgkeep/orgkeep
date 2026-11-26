package org.orgkeep.components.organisation.seats;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.SeatService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.Seats;
import org.orgkeep.api.model.UpdateSeatsRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The SEATS lock is one key per organisation, taken by both OrgMemberServiceImpl.add and
 * ServiceAccountServiceImpl.create (see docs/design/locks.md): this is the one test that exercises both call sites
 * together, with real services against the Keycloak of Dev Services. A spy on {@link KeycloakOrgs} holds the member
 * add right inside the join (SEATS still held at that point, see OrgMemberServiceImpl.addLocked), while the test
 * thread tries a service account creation on the same organisation: it never passes while the add is in progress.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatsLockTest {
    private static final String ORG = "it-seats-lock-org";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject SeatService seatService;
    @InjectSpy KeycloakOrgs orgs;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @AfterEach
    void cleanUp() {
        try {
            orgService.delete(testRealm.name(), ORG);
        } catch (OrgNotFoundException ignored) {
            // not created by this test
        }
        testRealm.resource().users().list(0, 1000)
                .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    /**
     * The add is held right inside the Keycloak call that joins the organisation, under SEATS exclusive (see
     * OrgMemberServiceImpl.addLocked). A concurrent service account creation of the same organisation cannot get
     * SEATS and, held longer than max-wait (2 s by default), is refused with "change in progress": the two call
     * sites never both hold the org's seat counts at once. Released, the add finishes and the creation then
     * succeeds.
     */
    @Test
    void memberAddAndServiceAccountCreationOfTheSameOrgAreSerialised() throws Exception {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));

        CountDownLatch addInside = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    addInside.countDown();
                    releaseAdd.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> add = executor.submit(() -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("bob@it-seats-lock-org.test", "Bob", "Smith")));
            assertTrue(addInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> serviceAccountService.create(
                    testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null)));

            releaseAdd.countDown();
            add.get(30, TimeUnit.SECONDS);
        } finally {
            releaseAdd.countDown();
            executor.shutdownNow();
        }

        // released: the creation now goes through
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
    }

    /**
     * The core promise of the SEATS lock: two adds of the same organisation never both pass the seat check with the
     * last seat. The first is held inside the Keycloak call that joins the organisation (after the seat check
     * passed, like above); the test keeps the second waiting on the lock until its own max-wait (2 s by default)
     * runs out, so it always sees "change in progress", not "no seat left" (which it would get if it waited past
     * the first's release and re-checked the now-exhausted seat). Either way exactly one member ends up added.
     */
    @Test
    void secondAddWithOneSeatLeftIsRefusedWhileTheFirstIsInProgress() throws Exception {
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "owner@it-seats-lock-org.test", "changeme", "Olivia", "Owner", 2, null));
        // the owner already takes one of the two seats

        CountDownLatch addInside = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    addInside.countDown();
                    releaseAdd.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstAdd = executor.submit(() -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("bob@it-seats-lock-org.test", "Bob", "Smith")));
            assertTrue(addInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("carol@it-seats-lock-org.test", "Carol", "Test")));

            releaseAdd.countDown();
            firstAdd.get(30, TimeUnit.SECONDS);
        } finally {
            releaseAdd.countDown();
            executor.shutdownNow();
        }

        assertEquals(2, testRealm.resource().users().list(0, 1000).size());
    }

    /**
     * SeatServiceImpl.update takes ORG exclusively; OrgMemberServiceImpl.add takes it shared, for the whole call
     * (see its javadoc). A PUT of the seats while an add is in progress on the same organisation cannot get its
     * exclusive lock and is refused "change in progress"; once the add is done, the same call succeeds.
     */
    @Test
    void updatingSeatsWhileAnAddIsInProgressIsRefusedThenAppliedAfter() throws Exception {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));

        CountDownLatch addInside = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    addInside.countDown();
                    releaseAdd.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> add = executor.submit(() -> memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("bob@it-seats-lock-org.test", "Bob", "Smith")));
            assertTrue(addInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> seatService.update(testRealm.name(), ORG, new UpdateSeatsRequest(5, null)));

            releaseAdd.countDown();
            add.get(30, TimeUnit.SECONDS);
        } finally {
            releaseAdd.countDown();
            executor.shutdownNow();
        }

        // the add is done, ORG is free again: the same call now goes through
        Seats seats = seatService.update(testRealm.name(), ORG, new UpdateSeatsRequest(5, null));
        assertEquals(5, seats.members().max());
    }
}
