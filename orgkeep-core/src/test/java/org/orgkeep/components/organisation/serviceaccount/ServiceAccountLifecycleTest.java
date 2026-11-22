package org.orgkeep.components.organisation.serviceaccount;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Service accounts and the lifecycle of their org: disabling, enabling and deleting it, and the locks. The grants of
 * a disabled service account are in ServiceAccountTokenTest.
 * <p>
 * One realm for the whole class (@BeforeAll); the org is rebuilt before every test (@BeforeEach) and torn down
 * after it (@AfterEach), since some tests delete it.
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountLifecycleTest {
    private static final String ORG = "acme";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject org.orgkeep.api.UserGroupService userGroupService;
    // spies on the real repository: they call Keycloak as usual, a test can block or fail one call
    @InjectSpy KeycloakOrgs orgs;

    private TestRealm testRealm;
    private String paulId;
    private String batchUserId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        paulId = memberService.add(realm(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        batchUserId = create("batch").serviceAccount().userId();
        userGroupService.create(realm(), ORG, new org.orgkeep.api.model.CreateUserGroupRequest("ops"));
    }

    @AfterEach
    void cleanUp() {
        try {
            try {
                orgService.delete(realm(), ORG);
            } catch (OrgNotFoundException ignored) {
                // already deleted by this test
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

    // the service accounts come through the loop of the members: disabled, listed, and enabled again with the org
    @Test
    void disablingTheOrgDisablesItsServiceAccountsAndEnablingRestoresThem() {
        orgService.setEnabled(realm(), ORG, false);

        assertFalse(enabled(batchUserId));
        assertFalse(enabled(paulId));
        assertTrue(disabledMembers().contains(batchUserId));
        assertFalse(serviceAccountService.get(realm(), ORG, "batch").enabled());

        orgService.setEnabled(realm(), ORG, true);

        assertTrue(enabled(batchUserId));
        assertTrue(enabled(paulId));
        assertEquals(List.of(), disabledMembers());
    }

    // disabled by its admin first: not listed, and still disabled once the org is enabled again
    @Test
    void aServiceAccountDisabledByItsAdminStaysDisabled() {
        serviceAccountService.setEnabled(realm(), ORG, "batch", false);

        orgService.setEnabled(realm(), ORG, false);
        assertFalse(disabledMembers().contains(batchUserId));
        orgService.setEnabled(realm(), ORG, true);

        assertFalse(enabled(batchUserId));
        assertTrue(enabled(paulId));
    }

    // refused in both directions while the org is disabled (the endpoint never writes the org), and while it is being
    // enabled again (the list is not empty)
    @Test
    void statusChangeIsRefusedWhileTheOrgIsDisabled() {
        orgService.setEnabled(realm(), ORG, false);

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> serviceAccountService.setEnabled(realm(), ORG, "batch", true));
        assertThrowsExactly(OperationNotAllowedException.class,
                () -> serviceAccountService.setEnabled(realm(), ORG, "batch", false));
        assertFalse(enabled(batchUserId), "the refused call changed nothing");
    }

    // deleted while listed among the disabled members: the id leaves the list, so the enabling of the org does not look
    // for a deleted user
    @Test
    void deletedServiceAccountLeavesTheDisabledMembers() {
        orgService.setEnabled(realm(), ORG, false);
        assertTrue(disabledMembers().contains(batchUserId));

        serviceAccountService.delete(realm(), ORG, "batch");

        assertFalse(disabledMembers().contains(batchUserId));
        assertTrue(disabledMembers().contains(paulId));
        orgService.setEnabled(realm(), ORG, true);
        assertTrue(enabled(paulId));
    }

    @Test
    void deletingTheOrgDeletesItsServiceAccounts() {
        create("worker");

        orgService.delete(realm(), ORG);

        assertTrue(testRealm.resource().clients().findByClientId("org-acme.batch").isEmpty());
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.worker").isEmpty());
        assertTrue(testRealm.resource().users().search("service-account-org-acme", true).isEmpty());
        assertTrue(orgs.findByName(realm(), ORG).isEmpty());
    }

    // The service accounts go first: a failure right after (the spy makes the deletion of the org fail once) leaves the
    // org in place, and the same call, repeated, finishes the job
    @Test
    void deletingTheOrgAgainFinishesAHalfDoneDeletion() {
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(orgs).delete(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> orgService.delete(realm(), ORG));
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.batch").isEmpty());
        assertTrue(orgs.findByName(realm(), ORG).isPresent());

        orgService.delete(realm(), ORG);

        assertTrue(orgs.findByName(realm(), ORG).isEmpty());
        assertTrue(testRealm.resource().groups().groups("acme", 0, 10).isEmpty());
    }

    // the disable is held inside (the spy blocks it while it reads the members, ORG is locked exclusively): a change on
    // a service account waits a short time for it, then gets the 409
    @Test
    void statusChangeAndDeleteAreRefusedWhileTheOrgIsBeingDisabled() throws Exception {
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
            Future<?> disable = executor.submit(() -> orgService.setEnabled(realm(), ORG, false));
            assertTrue(disableInside.await(30, TimeUnit.SECONDS));

            assertThrowsExactly(ResourceBusyException.class,
                    () -> serviceAccountService.setEnabled(realm(), ORG, "batch", false));
            assertThrowsExactly(ResourceBusyException.class, () -> serviceAccountService.delete(realm(), ORG, "batch"));
            assertThrowsExactly(ResourceBusyException.class, () -> serviceAccountService.regenerateSecret(realm(), ORG, "batch"));
            assertThrowsExactly(ResourceBusyException.class, () -> create("worker"));
            // the team write takes ORG shared, TEAM shared and the user exclusively: refused as well
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.addServiceAccount(realm(), ORG, "ops", "batch"));
            assertThrowsExactly(ResourceBusyException.class,
                    () -> userGroupService.removeServiceAccount(realm(), ORG, "ops", "batch"));

            releaseDisable.countDown();
            disable.get(60, TimeUnit.SECONDS);
        } finally {
            releaseDisable.countDown();
            executor.shutdownNow();
        }
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.batch").size() == 1, "still there");
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.worker").isEmpty());
        assertFalse(enabled(batchUserId));
    }

    private ServiceAccountCreated create(String name) {
        return serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest(name, null, null));
    }

    private boolean enabled(String userId) {
        return testRealm.resource().users().get(userId).toRepresentation().isEnabled();
    }

    private List<String> disabledMembers() {
        return DisabledMembers.of(orgs.getByName(realm(), ORG));
    }

    private String realm() {
        return testRealm.name();
    }
}
