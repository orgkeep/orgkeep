package org.orgkeep.components.organisation.usergroup.team;

import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakGroups;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The TEAM locks and the heavy operations on an org (#27), with real services against the Keycloak of Dev Services, in
 * a throwaway realm per test. A lock belongs to a thread, so "another call in progress" is a second thread; the spy
 * on {@link KeycloakGroups} holds the first call at a precise point (a latch) while the test makes the second call.
 * <p>
 * Setup: the catalog "prod" with two simple products "a" and "b", assigned to the org "acme", which has two teams,
 * "ops" and "dev".
 */
@QuarkusTest
class TeamLocksTest {
    private static final String CATALOG = "prod";
    private static final String ORG = "acme";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject TeamService teamService;
    @Inject UserGroupService userGroupService;
    @Inject ResourceLocks locks;
    @Inject OrgkeepConfig config;
    @InjectSpy KeycloakGroups groups;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        catalogService.create(realm(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        for (String product : new String[]{"a", "b"}) {
            productService.create(realm(), CATALOG, new CreateProductRequest(product, product, null));
            productService.assign(realm(), ORG, CATALOG, product, new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
        }
        userGroupService.create(realm(), ORG, new CreateUserGroupRequest("ops"));
        userGroupService.create(realm(), ORG, new CreateUserGroupRequest("dev"));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // Two org admins on two different teams of the same org: ORG shared + TEAM exclusive on two keys, no waiting
    @Test
    void twoTeamsOfTheSameOrgDoNotWaitForEachOther() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheFirstWrite(inside, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = inRequest(executor, () -> grant("ops", "a"));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            // the call returns while the first one is still held on its latch: the second team did not wait for it
            grant("dev", "b");
            assertFalse(first.isDone(), "the first write should still be held");
            assertNotNull(teamAttributes("dev").get("grant:prod:b"));

            release.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertNotNull(teamAttributes("ops").get("grant:prod:a"));
    }

    // The bug of #27: two products given to the same team at once. The second waits less than max-wait for the
    // first, then reads the attributes the first wrote: both choices are kept
    @Test
    void twoProductsGivenToTheSameTeamAreBothKept() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheFirstWrite(inside, release);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = inRequest(executor, () -> grant("ops", "a"));
            assertTrue(inside.await(30, TimeUnit.SECONDS));
            Future<?> second = inRequest(executor, () -> grant("ops", "b"));
            Thread.sleep(500);
            release.countDown();

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        Map<String, List<String>> attributes = teamAttributes("ops");
        assertNotNull(attributes.get("grant:prod:a"));
        assertNotNull(attributes.get("grant:prod:b"));
    }

    // Held for longer than max-wait (2 s by default): the second is refused, the first choice is intact
    @Test
    void secondWriteOnTheSameTeamIsRefusedWhenTheFirstTakesTooLong() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheFirstWrite(inside, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = inRequest(executor, () -> grant("ops", "a"));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> grant("ops", "b"));

            release.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        Map<String, List<String>> attributes = teamAttributes("ops");
        assertNotNull(attributes.get("grant:prod:a"));
        assertNull(attributes.get("grant:prod:b"));
    }

    // Giving a product on one team holds ORG shared + that TEAM only: it must not rewrite the other teams from a
    // snapshot. "dev" is made stale (its pool attribute removed) and stays so after "ops" is written
    @Test
    void grantingAProductDoesNotRealignTheOtherTeams() {
        grant("dev", "a");
        String devId = testRealm.resource().getGroupByPath("/" + ORG + "/user-groups/dev").getId();
        Map<String, List<String>> stale = new HashMap<>(teamAttributes("dev"));
        assertNotNull(stale.remove("prod.a.quotas"));
        groups.replaceAttributes(realm(), devId, stale);

        grant("ops", "a");

        assertNull(teamAttributes("dev").get("prod.a.quotas"));
        assertNotNull(teamAttributes("ops").get("prod.a.quotas"));
    }

    // A renegotiation holds the org exclusively: a team write of the org is refused after max-wait
    @Test
    void teamWriteIsRefusedWhileTheOrgIsRenegotiated() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheFirstWrite(inside, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // a is already assigned: this is a renegotiation, whose first write is the assignment group
            Future<?> renegotiation = inRequest(executor, () -> productService.assign(realm(), ORG, CATALOG, "a",
                    new AssignProductRequest(2000L, QuotaCycle.MONTHLY)));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class, () -> grant("ops", "b"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.create(realm(), ORG, new CreateUserGroupRequest("qa")));

            release.countDown();
            renegotiation.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertNull(teamAttributes("ops").get("grant:prod:b"));
    }

    // The other way round: a team write holds the org shared, the renegotiation and the unassignment (a single
    // immediate try) are refused at once
    @Test
    void renegotiationAndUnassignAreRefusedAtOnceWhileATeamIsWritten() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheFirstWrite(inside, release);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = inRequest(executor, () -> grant("ops", "a"));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            long start = System.nanoTime();
            assertThrowsExactly(ResourceBusyException.class, () -> productService.assign(realm(), ORG, CATALOG, "a",
                    new AssignProductRequest(2000L, QuotaCycle.MONTHLY)));
            assertThrowsExactly(ResourceBusyException.class, () -> productService.unassign(realm(), ORG, CATALOG, "b"));
            // a call that waited would have taken at least max-wait
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < maxWaitMillis(), "the refusal waited");

            release.countDown();
            write.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        // nothing was changed by the refused calls
        assertEquals(1000L, productService.listAssigned(realm(), ORG).stream()
                .filter(product -> "a".equals(product.product())).findFirst().orElseThrow().quota());
        assertEquals(2, productService.listAssigned(realm(), ORG).size());
    }

    // Every write on a team is refused while another change holds the team: the group is not created, deleted or
    // written meanwhile
    @Test
    void writesOnATeamAreRefusedWhileTheTeamIsLocked() throws Exception {
        grant("ops", "a");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.team(realm(), ORG, "ops", LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class, () -> grant("ops", "b"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> teamService.revokeProduct(realm(), ORG, "ops", CATALOG, "a"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> teamService.grantCatalogRole(realm(), ORG, "ops", CATALOG, "any"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> teamService.revokeCatalogRole(realm(), ORG, "ops", CATALOG, "any"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.delete(realm(), ORG, "ops"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.create(realm(), ORG, new CreateUserGroupRequest("ops")));
            assertNotNull(teamAttributes("ops").get("grant:prod:a"));
        } finally {
            executor.shutdownNow();
        }
        // released: the same call goes through
        teamService.revokeProduct(realm(), ORG, "ops", CATALOG, "a");
    }

    // The heavy operations on the org take a single try: refused at once while another change holds the org
    @Test
    void assignAndUnassignAreRefusedAtOnceWhileTheOrgIsLocked() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), ORG, LockMode.SHARED))) {
            long start = System.nanoTime();
            assertThrowsExactly(ResourceBusyException.class, () -> productService.assign(realm(), ORG, CATALOG, "a",
                    new AssignProductRequest(5L, QuotaCycle.DAILY)));
            assertThrowsExactly(ResourceBusyException.class, () -> productService.unassign(realm(), ORG, CATALOG, "a"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < maxWaitMillis(), "the refusal waited");
        } finally {
            executor.shutdownNow();
        }
        productService.unassign(realm(), ORG, CATALOG, "a");
    }

    private long maxWaitMillis() {
        return config.locks().maxWait().toMillis();
    }

    private String realm() {
        return testRealm.name();
    }

    private void grant(String team, String product) {
        teamService.grantProduct(realm(), ORG, team, CATALOG, product, GrantProductRequest.allFeatures());
    }

    private Map<String, List<String>> teamAttributes(String team) {
        return testRealm.resource().getGroupByPath("/" + ORG + "/user-groups/" + team).getAttributes();
    }

    /** Holds the first write of a group attribute until the latch is released (later calls are not held). */
    private void holdTheFirstWrite(CountDownLatch inside, CountDownLatch release) {
        Mockito.doAnswer(invocation -> {
                    inside.countDown();
                    release.await();
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(groups).replaceAttributes(anyString(), anyString(), any());
    }

    /** Runs the action on another thread inside a request context, like an HTTP request (see UserLocksTest). */
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

    /** Takes a lock on its own thread and keeps it until closed, so the test thread really finds it held. */
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
