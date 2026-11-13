package org.orgkeep.components.organisation.member;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.MemberOfAnotherOrgException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The USER locks, with real services against the Keycloak of Dev Services, in a throwaway realm per test. A lock
 * belongs to a thread, so "another call in progress" is a second thread; the spy on {@link KeycloakOrgs} holds the
 * first call at a precise point (a latch) while the test makes the second call.
 */
@QuarkusTest
class UserLocksTest {
    private static final String EMAIL = "paul@acme.test";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject ResourceLocks locks;
    @InjectSpy KeycloakOrgs orgs;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        realmProvisioner.ensureDefaults(testRealm.name());
        orgService.create(testRealm.name(), new CreateOrgRequest("acme", Set.of(), null, null));
        orgService.create(testRealm.name(), new CreateOrgRequest("globex", Set.of(), null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // The bug of #28: two org admins add the same email to two orgs at once. The first add is held right before the
    // user joins its org. The second waits for the email, less than max-wait: it then finds the user in another org
    // and gets the neutral 409 of #21. The user belongs to one org only
    @Test
    void sameEmailAddedToTwoOrgsAtTheSameTimeJoinsOnlyOne() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        holdTheFirstJoin(firstInside, releaseFirst);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = inRequest(executor, () -> add("acme"));
            assertTrue(firstInside.await(30, TimeUnit.SECONDS));

            Future<?> second = inRequest(executor, () -> add("globex"));
            Thread.sleep(300);
            releaseFirst.countDown();

            first.get(30, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> second.get(30, TimeUnit.SECONDS));
            assertInstanceOf(MemberOfAnotherOrgException.class, failure.getCause());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of("acme"), orgs.organisationsOf(testRealm.name(), userId(EMAIL)));
    }

    // Held for longer than max-wait (2 s by default), the second add is refused with "change in progress", and the
    // user is still in the first org only
    @Test
    void sameEmailAddedWhileTheFirstAddTakesTooLongIsRefused() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        holdTheFirstJoin(firstInside, releaseFirst);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = inRequest(executor, () -> add("acme"));
            assertTrue(firstInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> add("globex"));

            releaseFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of("acme"), orgs.organisationsOf(testRealm.name(), userId(EMAIL)));
    }

    // The email is the key, whatever its case; and the refusal comes before anything is written: no user is created
    @Test
    void addIsRefusedWhileTheEmailIsLockedAndCreatesNoUser() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor,
                LockRequest.userByEmail(testRealm.name(), "PAUL@acme.test", LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class, () -> add("acme"));
            assertTrue(testRealm.resource().users().searchByEmail(EMAIL, true).isEmpty());
        } finally {
            executor.shutdownNow();
        }
        // released: the same call goes through
        add("acme");
        assertEquals(List.of("acme"), orgs.organisationsOf(testRealm.name(), userId(EMAIL)));
    }

    // The owner of a new org is locked by email like a member: refused while the same email is being added, and no
    // org is created
    @Test
    void createWithAnOwnerIsRefusedWhileTheEmailIsLocked() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor,
                LockRequest.userByEmail(testRealm.name(), EMAIL, LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class, () -> orgService.create(testRealm.name(),
                    new CreateOrgRequest("beta", Set.of(), EMAIL, null, "Paul", "Test")));
            assertTrue(orgs.findByName(testRealm.name(), "beta").isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    // The id lock taken once the user is found: a user of no org (added, then removed) whose id is locked cannot be
    // added to an org nor made the owner of a new one, and nothing is written. The email is not locked here
    @Test
    void existingUserLockedByIdCannotBeAddedNorMadeOwner() throws Exception {
        String paulId = add("acme").id();
        memberService.remove(testRealm.name(), "acme", paulId);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor,
                LockRequest.userById(testRealm.name(), paulId, LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class, () -> add("globex"));
            assertEquals(List.of(), orgs.organisationsOf(testRealm.name(), paulId));

            assertThrowsExactly(ResourceBusyException.class, () -> orgService.create(testRealm.name(),
                    new CreateOrgRequest("beta", Set.of(), EMAIL, null, "Paul", "Test")));
            assertTrue(orgs.findByName(testRealm.name(), "beta").isEmpty());
        } finally {
            executor.shutdownNow();
        }
        // released: the same add goes through
        add("globex");
        assertEquals(List.of("globex"), orgs.organisationsOf(testRealm.name(), paulId));
    }

    // A user being removed from its org cannot join a team of it meanwhile (it would keep the rights of the team):
    // the removal is held right before the membership is removed, and holds the user
    @Test
    void userBeingRemovedCannotJoinATeam() throws Exception {
        String paulId = add("acme").id();
        userGroupService.create(testRealm.name(), "acme", new CreateUserGroupRequest("ops"));

        CountDownLatch removalInside = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    removalInside.countDown();
                    releaseRemoval.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).removeMember(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> removal = inRequest(executor, () -> memberService.remove(testRealm.name(), "acme", paulId));
            assertTrue(removalInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.addMember(testRealm.name(), "acme", "ops", paulId));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.removeMember(testRealm.name(), "acme", "ops", paulId));

            releaseRemoval.countDown();
            removal.get(30, TimeUnit.SECONDS);
        } finally {
            releaseRemoval.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(), orgs.organisationsOf(testRealm.name(), paulId));
        assertTrue(testRealm.groupPaths(paulId).stream().noneMatch(path -> path.startsWith("/acme")));
    }

    // Granting or revoking a catalog role on a user is refused while a change is in progress on this user
    @Test
    void catalogRoleGrantAndRevokeAreRefusedWhileTheUserIsLocked() throws Exception {
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        catalogRoleService.create(testRealm.name(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
        String paulId = add("acme").id();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor,
                LockRequest.userById(testRealm.name(), paulId, LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class,
                    () -> catalogRoleService.grant(testRealm.name(), "prod", "anonymous", paulId));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> catalogRoleService.revoke(testRealm.name(), "prod", "anonymous", paulId));
            assertTrue(catalogRoleService.holders(testRealm.name(), "prod", "anonymous").isEmpty());
        } finally {
            executor.shutdownNow();
        }
        catalogRoleService.grant(testRealm.name(), "prod", "anonymous", paulId);
        assertEquals(1, catalogRoleService.holders(testRealm.name(), "prod", "anonymous").size());
    }

    /** Holds the first real join of a member to an org until the latch is released (later calls are not held). */
    private void holdTheFirstJoin(CountDownLatch inside, CountDownLatch release) {
        Mockito.doAnswer(invocation -> {
                    inside.countDown();
                    release.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(orgs).addMember(anyString(), anyString(), anyString());
    }

    private OrgMember add(String org) {
        return memberService.add(testRealm.name(), org, new AddMemberRequest(EMAIL, "Paul", "Test"));
    }

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }

    /** Runs the action on another thread inside a request context, like an HTTP request (see OrgStatusTest). */
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

    /**
     * Takes locks on its own thread and keeps them until closed: a lock belongs to its thread, so the test thread
     * (the "second call") really finds them held.
     */
    private final class LockHolder implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> held;

        LockHolder(ExecutorService executor, LockRequest request) throws Exception {
            CountDownLatch taken = new CountDownLatch(1);
            held = executor.submit(() -> {
                try (HeldLocks ignored = locks.acquire(request)) {
                    taken.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(taken.await(30, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
        }
    }
}
