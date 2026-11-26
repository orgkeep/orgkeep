package org.orgkeep.components.organisation.suspension;

import org.orgkeep.api.AccountSuspensionService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.Page;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a suspension interacts with the status of the organisation (disable / enable), add, remove and delete: the
 * scenarios of docs/design/members.md, "Suspension par le realm owner". The order of the checks and resumability
 * are {@link AccountSuspensionServiceTest}; security and status codes over REST are {@link SuspensionsResourceTest}.
 * <p>
 * One realm for the whole class (@BeforeAll); the fixture (the org "acme", a member paul) is rebuilt before every
 * test (@BeforeEach) and torn down after it (@AfterEach).
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspensionLifecycleTest {
    private static final String ORG = "acme";
    private static final String REALM_OWNER_ID = "boss-id";
    private static final String ADMIN_ID = "alice-id";

    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject UserGroupService userGroupService;
    @Inject AccountSuspensionService suspensionService;
    @Inject KeycloakOrgs orgs;
    @InjectMock CurrentRealm currentRealm;

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
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String org : List.of(ORG, "beta")) {
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

    // ---- lift restores what the org had decided

    @Test
    void liftOfAnEnabledBeforeAccountEnablesIt() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);

        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    @Test
    void liftOfAnAdminDisabledBeforeAccountStaysDisabled() {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, ADMIN_ID);
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);

        assertEquals(Boolean.FALSE, lifted.enabled());
    }

    @Test
    void orgAdminDisablesWhileSuspendedThenLiftStaysDisabled() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        memberService.setEnabled(testRealm.name(), ORG, paulId, false, ADMIN_ID);

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.FALSE, lifted.enabled());
    }

    @Test
    void orgAdminDisablesThenRealmOwnerSuspendsThenLiftStaysDisabled() {
        memberService.setEnabled(testRealm.name(), ORG, paulId, false, ADMIN_ID);

        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.FALSE, lifted.enabled());
    }

    // an org admin cannot enable a suspended account, and can still disable it (recorded)
    @Test
    void orgAdminCannotEnableASuspendedAccountButCanDisableIt() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        org.junit.jupiter.api.Assertions.assertThrows(org.orgkeep.api.exceptions.OperationNotAllowedException.class,
                () -> memberService.setEnabled(testRealm.name(), ORG, paulId, true, ADMIN_ID));
        OrgMember disabledAgain = memberService.setEnabled(testRealm.name(), ORG, paulId, false, ADMIN_ID);
        assertEquals(Boolean.FALSE, disabledAgain.enabled());
        assertNotNull(disabledAgain.suspension());
    }

    // ---- org disable / enable

    @Test
    void orgDisabledThenEnabledWhileSuspendedStaysDisabledThenLiftEnables() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        orgService.setEnabled(testRealm.name(), ORG, false);
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "still suspended");

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    @Test
    void suspendedThenOrgDisabledThenLiftStaysDisabledThenOrgEnabledEnables() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        orgService.setEnabled(testRealm.name(), ORG, false);

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.FALSE, lifted.enabled(), "the org is still disabled");

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertTrue(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "the lift already ended the suspension");
    }

    // suspended while the org's own disable already listed and disabled the member: orgChoice is "enabled" (not "an
    // org admin disabled it"), so lifting once the org is enabled again restores it
    @Test
    void suspendedWhileListedInDisabledMembersIsRestoredOnceLifted() {
        orgService.setEnabled(testRealm.name(), ORG, false);

        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled(), "still suspended");

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    // ---- remove and add

    @Test
    void removingASuspendedMemberKeepsItSuspendedAndDisabled() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        memberService.remove(testRealm.name(), ORG, paulId);

        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
    }

    @Test
    void removingASuspendedListedMemberKeepsItSuspendedAndDisabled() {
        orgService.setEnabled(testRealm.name(), ORG, false);
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        memberService.remove(testRealm.name(), ORG, paulId);

        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
        // a member who left the org belongs to no list any more, suspended or not (OrgMemberServiceImpl.leaveDisabledMembers)
        assertFalse(DisabledMembers.of(orgs.getByName(testRealm.name(), ORG)).contains(paulId), "left disabled-members");
    }

    @Test
    void reAddingASuspendedMemberToTheSameOrgStaysDisabled() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        memberService.remove(testRealm.name(), ORG, paulId);

        OrgMember added = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test"));

        assertEquals(paulId, added.id());
        assertEquals(Boolean.FALSE, added.enabled());
        assertNotNull(added.suspension());

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), ORG, paulId);
        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    @Test
    void reAddingASuspendedMemberToAnotherOrgStaysDisabled() {
        orgService.create(testRealm.name(), new CreateOrgRequest("beta", Set.of(), null, null));
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        memberService.remove(testRealm.name(), ORG, paulId);

        OrgMember added = memberService.add(testRealm.name(), "beta", new AddMemberRequest("paul@acme.test", "Paul", "Test"));

        assertEquals(Boolean.FALSE, added.enabled());
        assertNotNull(added.suspension());
    }

    @Test
    void suspendedUserMadeOwnerOfANewOrgStaysDisabled() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);
        memberService.remove(testRealm.name(), ORG, paulId);

        orgService.create(testRealm.name(),
                new CreateOrgRequest("beta", Set.of(), "paul@acme.test", null, "Paul", "Test"));

        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());

        OrgMember lifted = suspensionService.liftMember(testRealm.name(), "beta", paulId);
        assertEquals(Boolean.TRUE, lifted.enabled());
    }

    // ---- org deletion

    @Test
    void orgDeletedKeepsTheSuspendedPersonSuspendedAndDisabled() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        orgService.delete(testRealm.name(), ORG);

        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
        assertNotNull(testRealm.resource().users().get(paulId).toRepresentation()
                .getAttributes().get("suspension-reason"));
    }

    // ---- read paths: the suspension shows with no extra call, on every row that can carry it

    // the only scenario that would catch listUsersWithRoleFull silently falling back to a brief read: a group row
    // (paul, through /acme/user-groups/users) is already covered by SuspensionsResourceTest
    @Test
    void suspensionShowsOnTheOwnerRowOfTheUnfilteredMembersList() {
        suspensionService.suspendMember(testRealm.name(), ORG, ownerId, "Reason", REALM_OWNER_ID);

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, null, 0, 100);

        OrgMember owner = page.items().stream().filter(member -> member.id().equals(ownerId)).findFirst().orElseThrow();
        assertEquals("Reason", owner.suspension().reason());
    }

    // OrgServiceImpl.owners (OrgDetails.get), a different read path from the members list above
    @Test
    void suspensionShowsOnTheOwnersOfOrgDetails() {
        suspensionService.suspendMember(testRealm.name(), ORG, ownerId, "Reason", REALM_OWNER_ID);

        OrgMember owner = orgService.get(testRealm.name(), ORG).owners().stream()
                .filter(member -> member.id().equals(ownerId)).findFirst().orElseThrow();
        assertEquals("Reason", owner.suspension().reason());
    }

    @Test
    void suspensionShowsOnASearchResult() {
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, "paul", null, 0, 100);

        assertEquals(1, page.items().size());
        assertEquals("Reason", page.items().getFirst().suspension().reason());
    }

    @Test
    void suspensionShowsOnATeamResult() {
        userGroupService.create(testRealm.name(), ORG, new CreateUserGroupRequest("sales"));
        userGroupService.addMember(testRealm.name(), ORG, "sales", paulId);
        suspensionService.suspendMember(testRealm.name(), ORG, paulId, "Reason", REALM_OWNER_ID);

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, "sales", 0, 100);

        assertEquals(1, page.items().size());
        assertEquals("Reason", page.items().getFirst().suspension().reason());
    }

    @Test
    void suspensionShowsOnTheServiceAccountsListAndGet() {
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        suspensionService.suspendServiceAccount(testRealm.name(), ORG, "batch", "Abuse", REALM_OWNER_ID);

        List<ServiceAccount> list = serviceAccountService.list(testRealm.name(), ORG);
        assertEquals("Abuse", list.stream().filter(sa -> sa.name().equals("batch")).findFirst().orElseThrow()
                .suspension().reason());

        ServiceAccount got = serviceAccountService.get(testRealm.name(), ORG, "batch");
        assertEquals("Abuse", got.suspension().reason());
    }

    // ---- service accounts: the org admin's setEnabled respects a suspension too (ServiceAccountServiceImpl)

    @Test
    void orgAdminCannotEnableASuspendedServiceAccountButCanDisableIt() {
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        // enabled when suspended: orgChoice starts as "enabled"
        suspensionService.suspendServiceAccount(testRealm.name(), ORG, "batch", "Reason", REALM_OWNER_ID);

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> serviceAccountService.setEnabled(testRealm.name(), ORG, "batch", true));
        ServiceAccount disabledAgain = serviceAccountService.setEnabled(testRealm.name(), ORG, "batch", false);
        assertFalse(disabledAgain.enabled());
        assertNotNull(disabledAgain.suspension());

        // the org admin's disable recorded its choice: the lift respects it instead of the "enabled" default
        ServiceAccount lifted = suspensionService.liftServiceAccount(testRealm.name(), ORG, "batch");
        assertFalse(lifted.enabled(), "org admin's choice (disabled) recorded and restored on lift");
    }

    // ---- org disable loop: a service account suspended while the org's choice for it is still "enabled"

    @Test
    void orgDisableListsASuspendedServiceAccountWithOrgChoiceEnabled() {
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        suspensionService.suspendServiceAccount(testRealm.name(), ORG, "batch", "Reason", REALM_OWNER_ID);
        String saUserId = serviceAccountService.get(testRealm.name(), ORG, "batch").userId();

        orgService.setEnabled(testRealm.name(), ORG, false);
        OrganizationRepresentation org = orgs.getByName(testRealm.name(), ORG);
        assertTrue(DisabledMembers.of(org).contains(saUserId), "listed, so a later lift restores it once enabled");

        orgService.setEnabled(testRealm.name(), ORG, true);
        assertFalse(testRealm.resource().users().get(saUserId).toRepresentation().isEnabled(), "still suspended");

        ServiceAccount lifted = suspensionService.liftServiceAccount(testRealm.name(), ORG, "batch");
        assertTrue(lifted.enabled());
    }

    // ---- helpers

    private String userId(String email) {
        return testRealm.resource().users().searchByEmail(email, true).getFirst().getId();
    }
}
