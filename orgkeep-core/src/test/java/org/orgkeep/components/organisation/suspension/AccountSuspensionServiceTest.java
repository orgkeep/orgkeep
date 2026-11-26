package org.orgkeep.components.organisation.suspension;

import org.orgkeep.api.AccountSuspensionService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.exceptions.ServiceAccountNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
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

import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Service-level tests: the order of the checks, resumability after a failure, and the guard against a realm whose
 * user profile does not declare the suspension attributes. Security and status codes over REST:
 * {@link SuspensionsResourceTest}. Interaction with the status of the organisation, add, remove and delete:
 * {@link SuspensionLifecycleTest}.
 * <p>
 * One realm for the whole class (@BeforeAll); the fixture (the org "acme", its owner, a member paul, a service
 * account "batch") is rebuilt before every test (@BeforeEach) and torn down after it (@AfterEach).
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountSuspensionServiceTest {
    private static final String ORG = "acme";
    private static final String REALM_OWNER_ID = "boss-id";

    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject AccountSuspensionService suspensionService;
    @InjectMock CurrentRealm currentRealm;
    // spies call the real repositories, unless a test says otherwise
    @InjectSpy KeycloakUsers users;
    @InjectSpy KeycloakOrgs orgs;

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

    // ---- suspend: order of checks and basic behaviour

    @Test
    void suspendDisablesAndEndsSessions() {
        OrgMember suspended = suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Breached contract", REALM_OWNER_ID);

        assertEquals(Boolean.FALSE, suspended.enabled());
        assertEquals("Breached contract", suspended.suspension().reason());
        assertEquals(REALM_OWNER_ID, suspended.suspension().suspendedBy());
        assertNotNull(suspended.suspension().suspendedAt());
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
        Mockito.verify(users).logout(testRealm.name(), paulId);
    }

    @Test
    void anOwnerCanBeSuspended() {
        OrgMember suspended = suspensionService.suspendMember(testRealm.name(), ORG, ownerId, "Reason", REALM_OWNER_ID);
        assertEquals(Boolean.FALSE, suspended.enabled());
        assertEquals("ORG_OWNER", suspended.role().name());
    }

    @Test
    void suspendingAgainReplacesTheReasonAndKeepsTheRest() {
        OrgMember first = suspensionService.suspendMember(testRealm.name(), ORG, paulId, "First reason", REALM_OWNER_ID);

        OrgMember second = suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Second reason", "other-admin-id");

        assertEquals("Second reason", second.suspension().reason());
        assertEquals(first.suspension().suspendedAt(), second.suspension().suspendedAt());
        assertEquals(first.suspension().suspendedBy(), second.suspension().suspendedBy());
    }

    @Test
    void privilegedMemberIsRefusedWithANeutralMessage() {
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(paulId, "realm-owner");

        OperationNotAllowedException refused = assertThrowsExactly(OperationNotAllowedException.class,
                () -> suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID));
        assertFalse(refused.getMessage().contains("realm-owner"));
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
    }

    @Test
    void notAMemberOfThisOrgGets404() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest("globex", Set.of(), "gina@globex.test", null, "Gina", "Test"));
        String ginaId = userId("gina@globex.test");

        assertThrows(MemberNotFoundException.class,
                () -> suspensionService.suspendMember(testRealm.name(), ORG, ginaId, "Reason", REALM_OWNER_ID));
    }

    @Test
    void unknownOrgGets404() {
        assertThrows(OrgNotFoundException.class,
                () -> suspensionService.suspendMember(testRealm.name(), "nope", paulId, "Reason", REALM_OWNER_ID));
    }

    // ---- resumability

    // a failure between the record and the disable: the record is kept (reason, date, suspender), the account still
    // enabled; the same call, replayed, finishes the job
    @Test
    void suspendIsResumedAfterAFailureBetweenTheRecordAndTheDisable() {
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).setEnabled(anyString(), eq(paulId), eq(false));

        assertThrows(IllegalStateException.class,
                () -> suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID));
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "not disabled yet");

        OrgMember suspended = suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        assertEquals(Boolean.FALSE, suspended.enabled());
        assertEquals("Reason", suspended.suspension().reason());
    }

    // a failure between enabling again and removing the attributes: the account is enabled but still carries the
    // record; the same lift, replayed, finishes the job
    @Test
    void liftIsResumedAfterAFailureBetweenEnablingAndRemovingTheAttributes() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(users).removeAttributes(anyString(), eq(paulId), anyCollection());

        assertThrows(IllegalStateException.class, () -> suspensionService.liftMember(testRealm.name(), ORG, paulId));
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "enabled already");

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertNull(lifted.suspension());
        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    // ---- lift: idempotent, no record

    @Test
    void liftingAnUnsuspendedMemberChangesNothing() {
        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.TRUE, lifted.enabled());
        assertNull(lifted.suspension());
    }

    // ---- guard: a realm whose user profile lacks the declarations

    @Test
    void guardRefusesARealmWithoutTheDeclarations() {
        testRealm.removeSuspensionAttributes();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID));
        assertTrue(refused.getMessage().contains(testRealm.name()));
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "left untouched");
    }

    // ---- service accounts: same rules

    @Test
    void serviceAccountIsSuspendedAndLifted() {
        ServiceAccount suspended = suspensionService.suspendServiceAccount(testRealm.name(), ORG, "batch", "Abuse", REALM_OWNER_ID);
        assertFalse(suspended.enabled());
        assertEquals("Abuse", suspended.suspension().reason());

        ServiceAccount lifted = suspensionService.liftServiceAccount(testRealm.name(), ORG, "batch");
        assertTrue(lifted.enabled());
        assertNull(lifted.suspension());
    }

    @Test
    void unknownServiceAccountGets404() {
        assertThrows(ServiceAccountNotFoundException.class,
                () -> suspensionService.suspendServiceAccount(testRealm.name(), ORG, "nope", "Reason", REALM_OWNER_ID));
    }

    // ---- locks

    // a disable of the org holds ORG exclusively: suspending a member of it waits max-wait, then gets the 409
    @Test
    void suspendIsRefusedWhileTheOrgIsBeingDisabled() throws Exception {
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
                    () -> suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID));

            release.countDown();
            disable.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // ---- helpers

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }
}
