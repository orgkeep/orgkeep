package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.UserGroupNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.api.model.Page;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The three sources of the paged member list (members.md): no filter (owners then the default group, paged by
 * Keycloak), search and team (read whole and sliced here). Service-level, so a page can be sliced and compared
 * exactly, and the admin calls of a page counted with a spy, as in OrgServiceTest.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgMemberListTest {
    private static final String ORG = "it-list-org";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject KeycloakGroups groups;
    @InjectSpy KeycloakUsers usersSpy;
    @InjectSpy KeycloakClients clientsSpy;
    @InjectSpy KeycloakGroups groupsSpy;
    @InjectSpy KeycloakOrgs orgsSpy;
    @CacheName("client-uuid") Cache clientUuidCache;
    @CacheName("group-id") Cache groupIdCache;

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

    @Test
    void noFilterListsOwnersFirstThenEveryoneElseExactlyOnce() {
        String ownerId = orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "owner@it-list-org.test", "changeme", "Olivia", "Owner")).ownerId();
        List<String> memberIds = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            memberIds.add(memberService.add(testRealm.name(), ORG,
                    new AddMemberRequest("m" + i + "@it-list-org.test", "M" + i, "Test")).id());
        }

        // pages of 2, read back to back: every person (owner included) covered exactly once
        List<String> seen = new ArrayList<>();
        for (int first = 0; first < 5; first += 2) {
            Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, null, first, 2);
            assertEquals(5, page.total());
            page.items().forEach(member -> seen.add(member.id()));
        }
        assertEquals(ownerId, seen.get(0), "the owner comes first");
        assertEquals(List.copyOf(new java.util.LinkedHashSet<>(seen)), seen, "no one is shown twice");
        List<String> expected = new ArrayList<>(List.of(ownerId));
        expected.addAll(memberIds);
        assertEquals(Set.copyOf(expected), Set.copyOf(seen));
    }

    @Test
    void noFilterGivesEachMemberItsRole() {
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "owner@it-list-org.test", "changeme", "Olivia", "Owner"));
        String adminId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("emma@it-list-org.test", "Emma", "Test")).id();
        userGroupService.addMember(testRealm.name(), ORG, "admin", adminId);
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("hugo@it-list-org.test", "Hugo", "Test")).id();

        List<OrgMember> items = memberService.list(testRealm.name(), ORG, null, null, 0, 100).items();
        assertEquals(OrgRole.ORG_OWNER, roleOf(items, "owner@it-list-org.test"));
        assertEquals(OrgRole.ORG_ADMIN, roleOf(items, "emma@it-list-org.test"));
        assertEquals(OrgRole.ORG_USER, roleOf(items, "hugo@it-list-org.test"));
    }

    @Test
    void noFilterExcludesServiceAccountsFromItemsAndTotal() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        String paulId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@it-list-org.test", "Paul", "Test")).id();
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, null, 0, 100);

        assertEquals(1, page.total());
        assertEquals(List.of(paulId), page.items().stream().map(OrgMember::id).toList());
    }

    // the number of admin calls for a page of 2 must not depend on how many members the org has
    @Test
    void noFilterCallCountDoesNotGrowWithTheNumberOfMembers() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        for (int i = 1; i <= 2; i++) {
            memberService.add(testRealm.name(), ORG, new AddMemberRequest("s" + i + "@it-list-org.test", "S" + i, "Test"));
        }
        int callsWithFewMembers = adminCallsForAPageOf2();

        for (int i = 3; i <= 6; i++) {
            memberService.add(testRealm.name(), ORG, new AddMemberRequest("s" + i + "@it-list-org.test", "S" + i, "Test"));
        }
        int callsWithMoreMembers = adminCallsForAPageOf2();

        assertEquals(callsWithFewMembers, callsWithMoreMembers);
        // no credentials call: the pending rule reads the full representation already loaded, never Keycloak again
        Mockito.verify(usersSpy, Mockito.never()).hasPassword(anyString(), anyString());
    }

    /** Calls made by a page of 2 of the unfiltered list, id-caches invalidated first so the count is reproducible. */
    private int adminCallsForAPageOf2() {
        clientUuidCache.invalidateAll().await().indefinitely();
        groupIdCache.invalidateAll().await().indefinitely();
        Mockito.clearInvocations(usersSpy, clientsSpy, groupsSpy, orgsSpy);

        memberService.list(testRealm.name(), ORG, null, null, 0, 2);

        return Mockito.mockingDetails(usersSpy).getInvocations().size()
                + Mockito.mockingDetails(clientsSpy).getInvocations().size()
                + Mockito.mockingDetails(groupsSpy).getInvocations().size()
                + Mockito.mockingDetails(orgsSpy).getInvocations().size();
    }

    // an organisation created outside Orgkeep (or whose group was removed by hand) has no group "users"
    @Test
    void noFilterDegradesToOwnersOnlyWhenTheUsersGroupIsMissing() {
        String ownerId = orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), "owner@it-list-org.test", "changeme", "Olivia", "Owner")).ownerId();
        String usersGroupId = groups.findId(testRealm.name(), OrgGroups.users(ORG)).orElseThrow();
        groups.delete(testRealm.name(), OrgGroups.users(ORG), usersGroupId);

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, null, 0, 100);

        assertEquals(List.of(ownerId), page.items().stream().map(OrgMember::id).toList());
    }

    @Test
    void searchFindsByEmailAndByFirstName() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        String inesId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("ines@it-list-org.test", "Ines", "Martin")).id();
        memberService.add(testRealm.name(), ORG, new AddMemberRequest("bob@it-list-org.test", "Bob", "Smith"));

        assertEquals(List.of(inesId), ids(memberService.list(testRealm.name(), ORG, "ines@", null, 0, 100)));
        assertEquals(List.of(inesId), ids(memberService.list(testRealm.name(), ORG, "Ines", null, 0, 100)));
    }

    @Test
    void searchNeverReturnsAServiceAccount() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, "batch", null, 0, 100);

        assertEquals(0, page.total());
        assertEquals(List.of(), page.items());
    }

    @Test
    void searchSlicesAndCountsTheMatches() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        for (int i = 1; i <= 3; i++) {
            memberService.add(testRealm.name(), ORG, new AddMemberRequest("match" + i + "@it-list-org.test", "Match" + i, "Test"));
        }
        memberService.add(testRealm.name(), ORG, new AddMemberRequest("other@it-list-org.test", "Other", "Test"));

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, "match", null, 1, 1);

        assertEquals(3, page.total());
        assertEquals(1, page.items().size());
    }

    @Test
    void teamReturnsOnlyItsPeople() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        String paulId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@it-list-org.test", "Paul", "Test")).id();
        memberService.add(testRealm.name(), ORG, new AddMemberRequest("other@it-list-org.test", "Other", "Test"));
        userGroupService.create(testRealm.name(), ORG, new CreateUserGroupRequest("sales"));
        userGroupService.addMember(testRealm.name(), ORG, "sales", paulId);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("batch", null, null));
        userGroupService.addServiceAccount(testRealm.name(), ORG, "sales", "batch");

        Page<OrgMember> page = memberService.list(testRealm.name(), ORG, null, "sales", 0, 100);

        assertEquals(1, page.total());
        assertEquals(List.of(paulId), page.items().stream().map(OrgMember::id).toList());
    }

    @Test
    void unknownTeamThrowsNotFound() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));

        assertThrowsExactly(UserGroupNotFoundException.class,
                () -> memberService.list(testRealm.name(), ORG, null, "unknown", 0, 100));
    }

    @Test
    void searchAndTeamTogetherAreRefused() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));

        assertThrowsExactly(InvalidRequestException.class,
                () -> memberService.list(testRealm.name(), ORG, "a", "users", 0, 100));
    }

    private static OrgRole roleOf(List<OrgMember> members, String email) {
        return members.stream().filter(m -> email.equals(m.email())).findFirst().orElseThrow().role();
    }

    private static List<String> ids(Page<OrgMember> page) {
        return page.items().stream().map(OrgMember::id).toList();
    }
}
