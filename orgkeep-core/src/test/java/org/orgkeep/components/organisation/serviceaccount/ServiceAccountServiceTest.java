package org.orgkeep.components.organisation.serviceaccount;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.TokenPreviewService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ServiceAccountAlreadyExistsException;
import org.orgkeep.api.exceptions.ServiceAccountNotFoundException;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.UpdateServiceAccountRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * What the service writes in Keycloak (a real one, Dev Services), in one realm for the whole class: the client, its scopes
 * and mapper, the group membership, the rollback. The grants of the tokens are in ServiceAccountTokenTest.
 * <p>
 * The org, the catalogs and the product are created once (@BeforeAll); the service accounts, members and extra
 * clients a test creates are deleted after each test (@AfterEach).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountServiceTest {
    private static final String ORG = "acme";

    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject TokenPreviewService tokenPreviewService;
    @Inject KeycloakOrgs orgs;
    // a spy on the real repository: it calls Keycloak as usual, and a test can make one call fail
    @InjectSpy KeycloakClients clients;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        catalogService.create(realm(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(realm(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(realm(), "prod", new CreateProductRequest("billing", "Billing", null));
        productService.assign(realm(), ORG, "prod", "billing", new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
    }

    // Deletes the raw clients directly, not through the service: a service account that left the group of the org
    // (aClientWithTheAttributeButNoMembershipIsNotAServiceAccount) is invisible to serviceAccountService.list, and
    // would otherwise leak into the next test.
    @AfterEach
    void cleanUp() {
        testRealm.resource().clients().findAll().stream()
                .filter(client -> client.getClientId() != null
                        && (client.getClientId().startsWith("org-acme.") || client.getClientId().startsWith("org-globex.")))
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
        testRealm.resource().clients().findByClientId("console-client")
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
        // paul@acme.test is added by two tests: removing it from the org (not deleting the user) would leave it
        // behind for the second one, which would then reuse it instead of creating it, a different code path
        testRealm.resource().users().list(0, 1000)
                .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
        try {
            orgService.delete(realm(), "globex");
        } catch (OrgNotFoundException ignored) {
            // not created by this test
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void clientIsConfiguredForClientCredentialsOnly() {
        ServiceAccountCreated created = create("billing", List.of());

        ClientRepresentation client = client("org-acme.billing");
        assertEquals("org-acme.billing", created.serviceAccount().clientId());
        assertEquals("org-acme.billing", created.credentials().clientId());
        assertFalse(client.isPublicClient());
        assertEquals("client-secret", client.getClientAuthenticatorType());
        assertTrue(client.isServiceAccountsEnabled());
        assertFalse(client.isStandardFlowEnabled());
        assertFalse(client.isImplicitFlowEnabled());
        assertFalse(client.isDirectAccessGrantsEnabled());
        assertEquals("false", client.getAttributes().get("oauth2.device.authorization.grant.enabled"));
        assertEquals("false", client.getAttributes().get("oidc.ciba.grant.enabled"));
        assertFalse(client.isFullScopeAllowed());
        assertEquals("billing", client.getName());
        // the safety net, checked with the group whenever a service account is resolved
        assertEquals(ORG, client.getAttributes().get("org"));
    }

    // no profile, no email: only what a token of a service account needs. The catalogs are optional, the organization
    // scope (the native mapper of the claim) is a default scope, not an optional one
    @Test
    void scopesAreReduced() {
        create("billing", List.of());
        String uuid = client("org-acme.billing").getId();

        assertEquals(Set.of("basic", "roles", "service_account", "organization"), names(
                testRealm.resource().clients().get(uuid).getDefaultClientScopes()));
        Set<String> optional = names(testRealm.resource().clients().get(uuid).getOptionalClientScopes());
        assertEquals(Set.of("prod", "sandbox"), optional);
    }

    // the claim "organization" comes from the native mapper of the scope: the client has no mapper of its own
    @Test
    void clientHasNoMapperOfItsOwn() {
        create("billing", List.of());

        assertTrue(testRealm.resource().clients().get(client("org-acme.billing").getId()).getProtocolMappers().getMappers().isEmpty());
    }

    // the native mapper needs the user to be a member of the Keycloak organisation (else Keycloak refuses to
    // introspect the token): an UNMANAGED member, like the people
    @Test
    void userIsMemberOfTheKeycloakOrganisation() {
        ServiceAccountCreated created = create("billing", List.of());

        assertEquals(List.of("acme"), orgs.organisationsOf(realm(), created.serviceAccount().userId()));
    }

    // not people: the member list, the counts and the member endpoints never see them
    @Test
    void serviceAccountIsNeverAPerson() {
        String paulId = memberService.add(realm(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        ServiceAccountCreated created = create("billing", List.of());
        String saUserId = created.serviceAccount().userId();

        assertEquals(List.of(paulId), memberService.list(realm(), ORG, null, null, 0, 100).items().stream().map(OrgMember::id).toList());
        assertEquals(1, orgService.list(realm(), null, null, null, 0, 10).items().getFirst().memberCount());

        assertThrowsExactly(MemberNotFoundException.class, () -> memberService.remove(realm(), ORG, saUserId));
        assertThrowsExactly(MemberNotFoundException.class,
                () -> memberService.setEnabled(realm(), ORG, saUserId, false, paulId));
        assertThrowsExactly(MemberNotFoundException.class, () -> memberService.resendInvitation(realm(), ORG, saUserId));
        assertThrowsExactly(MemberNotFoundException.class,
                () -> userGroupService.addMember(realm(), ORG, "users", saUserId));
        assertThrowsExactly(MemberNotFoundException.class,
                () -> tokenPreviewService.preview(realm(), ORG, saUserId, List.of()));
        // nothing was done to it
        assertEquals(List.of("acme"), orgs.organisationsOf(realm(), saUserId));
        assertTrue(testRealm.resource().users().get(saUserId).toRepresentation().isEnabled());
    }

    // the membership of the group is the proof that the service account belongs to the org; the group adds nothing to a token
    @Test
    void userIsMemberOfTheGroupOfTheOrg() {
        ServiceAccountCreated created = create("billing", List.of());

        assertEquals("service-account-org-acme.billing",
                testRealm.resource().users().get(created.serviceAccount().userId()).toRepresentation().getUsername());
        assertEquals(Set.of("/acme/service-accounts"), testRealm.groupPaths(created.serviceAccount().userId()));
        GroupRepresentation group = testRealm.resource().getGroupByPath(OrgGroups.serviceAccounts(ORG));
        assertTrue(group.getAttributes() == null || group.getAttributes().isEmpty());
        assertTrue(group.getRealmRoles() == null || group.getRealmRoles().isEmpty());
        assertTrue(group.getClientRoles() == null || group.getClientRoles().isEmpty());
    }

    // an org created before the feature has no such group: made at the first service account
    @Test
    void groupIsCreatedOnDemandForAnOlderOrg() {
        testRealm.resource().groups().group(testRealm.resource().getGroupByPath(OrgGroups.serviceAccounts(ORG)).getId()).remove();

        ServiceAccountCreated created = create("billing", List.of());

        assertEquals(Set.of("/acme/service-accounts"), testRealm.groupPaths(created.serviceAccount().userId()));
        assertEquals(List.of("billing"), serviceAccountService.list(realm(), ORG).stream().map(ServiceAccount::name).toList());
    }

    @Test
    void listWithoutTheGroupIsEmpty() {
        testRealm.resource().groups().group(testRealm.resource().getGroupByPath(OrgGroups.serviceAccounts(ORG)).getId()).remove();

        assertEquals(List.of(), serviceAccountService.list(realm(), ORG));
    }

    @Test
    void defaultCatalogsAreReplacedByUpdateAndGoBackToOptional() {
        create("billing", List.of("prod"));
        String uuid = client("org-acme.billing").getId();
        assertEquals(Set.of("basic", "roles", "service_account", "organization", "prod"),
                names(testRealm.resource().clients().get(uuid).getDefaultClientScopes()));
        assertEquals(List.of("prod"), serviceAccountService.get(realm(), ORG, "billing").defaultCatalogs());

        ServiceAccount updated = serviceAccountService.update(realm(), ORG, "billing",
                new UpdateServiceAccountRequest("The billing batch", List.of()));

        assertEquals("The billing batch", updated.description());
        assertEquals(List.of(), updated.defaultCatalogs());
        assertEquals(Set.of("basic", "roles", "service_account", "organization"),
                names(testRealm.resource().clients().get(uuid).getDefaultClientScopes()));
        // back to optional, not removed
        assertEquals(Set.of("prod", "sandbox"), names(testRealm.resource().clients().get(uuid).getOptionalClientScopes()));
    }

    @Test
    void unknownCatalogIsRefused() {
        assertThrowsExactly(InvalidRequestException.class, () -> create("billing", List.of("nope")));
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.billing").isEmpty());
    }

    // "sandbox" exists, but the org has no product in it
    @Test
    void catalogWithoutAnAssignmentOfTheOrgIsRefused() {
        assertThrowsExactly(InvalidRequestException.class, () -> create("billing", List.of("sandbox")));
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.billing").isEmpty());

        create("billing", List.of());
        assertThrowsExactly(InvalidRequestException.class, () -> serviceAccountService.update(realm(), ORG, "billing",
                new UpdateServiceAccountRequest(null, List.of("sandbox"))));
    }

    // what makes a service account is the group of the org, not its username: a person's username is their email, and
    // "service-account-ci@..." stays listed, counted and manageable
    @Test
    void aPersonWhoseEmailStartsLikeAServiceAccountIsStillAPerson() {
        String paulId = memberService.add(realm(), ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        String ciId = memberService.add(realm(), ORG,
                new AddMemberRequest("service-account-ci@acme.test", "Ci", "Test")).id();
        create("billing", List.of());

        assertEquals(Set.of(paulId, ciId),
                memberService.list(realm(), ORG, null, null, 0, 100).items().stream().map(OrgMember::id).collect(Collectors.toSet()));
        assertEquals(2, orgService.list(realm(), null, null, null, 0, 10).items().getFirst().memberCount());
        assertEquals(List.of("billing"), serviceAccountService.list(realm(), ORG).stream().map(ServiceAccount::name).toList());

        assertFalse(memberService.setEnabled(realm(), ORG, ciId, false, paulId).enabled());
        memberService.remove(realm(), ORG, ciId);
        assertEquals(List.of(paulId), memberService.list(realm(), ORG, null, null, 0, 100).items().stream().map(OrgMember::id).toList());
    }

    // a client of the console whose service-account user is added to the org by hand is no service account of Orgkeep
    // either way: without the group it is a member like another. Not in the group /{org}/user-groups/users either
    // (added by hand, not through Orgkeep's add), so the unfiltered list does not show it: a known console limit
    // (members.md), shared with the member count, which still counts it. search still finds it: it reads the
    // organisation members directly, dropping only the ids of the service-accounts group
    @Test
    void aConsoleServiceAccountUserAddedToTheOrgIsNotInTheGroupSoNotFiltered() {
        String uuid = testRealm.createClient("console-client");
        var rep = testRealm.resource().clients().get(uuid).toRepresentation();
        rep.setServiceAccountsEnabled(true);
        testRealm.resource().clients().get(uuid).update(rep);
        String userId = testRealm.resource().clients().get(uuid).getServiceAccountUser().getId();
        orgs.addMember(realm(), testRealm.resource().organizations().search("acme", true, 0, 1).getFirst().getId(), userId);

        assertEquals(List.of(userId),
                memberService.list(realm(), ORG, "console-client", null, 0, 100).items().stream().map(OrgMember::id).toList());
        assertEquals(List.of(), serviceAccountService.list(realm(), ORG));
    }

    @Test
    void invalidNameIsRefusedEverywhere() {
        assertThrowsExactly(InvalidRequestException.class, () -> create("Billing", List.of()));
        assertThrowsExactly(InvalidRequestException.class, () -> serviceAccountService.get(realm(), ORG, "a/b"));
        assertThrowsExactly(InvalidRequestException.class, () -> serviceAccountService.get(realm(), ORG, "a:b"));
    }

    @Test
    void duplicateNameIsRefused() {
        create("billing", List.of());

        assertThrowsExactly(ServiceAccountAlreadyExistsException.class, () -> create("billing", List.of()));
    }

    // a client made by hand in the console with the clientId of a service account: taken, and never taken for one
    @Test
    void aClientOfTheConsoleIsNotAServiceAccount() {
        testRealm.createClient("org-acme.batch");

        assertThrowsExactly(ServiceAccountAlreadyExistsException.class, () -> create("batch", List.of()));
        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> serviceAccountService.get(realm(), ORG, "batch"));
        assertThrowsExactly(ServiceAccountNotFoundException.class,
                () -> serviceAccountService.regenerateSecret(realm(), ORG, "batch"));
        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> serviceAccountService.delete(realm(), ORG, "batch"));
        assertEquals(List.of(), serviceAccountService.list(realm(), ORG));
        assertTrue(testRealm.resource().clients().findByClientId("org-acme.batch").size() == 1, "left alone");
    }

    // the attribute alone is not enough either: the user must be in the group of the org
    @Test
    void aClientWithTheAttributeButNoMembershipIsNotAServiceAccount() {
        ServiceAccountCreated created = create("billing", List.of());
        testRealm.leaveGroup(created.serviceAccount().userId(), OrgGroups.serviceAccounts(ORG));

        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> serviceAccountService.get(realm(), ORG, "billing"));
        assertEquals(List.of(), serviceAccountService.list(realm(), ORG));
    }

    // the name of a service account of another org never resolves
    @Test
    void aServiceAccountOfAnotherOrgIsNotFound() {
        orgService.create(realm(), new CreateOrgRequest("globex", Set.of(), null, null));
        create("billing", List.of());

        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> serviceAccountService.get(realm(), "globex", "billing"));
        assertEquals(List.of(), serviceAccountService.list(realm(), "globex"));
    }

    @Test
    void listIsSortedByName() {
        create("zeta", List.of());
        create("alpha", List.of("prod"));

        List<ServiceAccount> all = serviceAccountService.list(realm(), ORG);

        assertEquals(List.of("alpha", "zeta"), all.stream().map(ServiceAccount::name).toList());
        assertEquals(List.of("prod"), all.getFirst().defaultCatalogs());
        assertTrue(all.stream().allMatch(ServiceAccount::enabled));
        assertNotNull(all.getFirst().userId());
    }

    // a failure after the client is created deletes it, with its user and membership; a retry starts clean
    @Test
    void failedCreationIsRolledBack() {
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(clients).regenerateSecret(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> create("billing", List.of()));

        assertTrue(testRealm.resource().clients().findByClientId("org-acme.billing").isEmpty());
        assertEquals(List.of(), serviceAccountService.list(realm(), ORG));
        // the group is kept, the retry works
        ServiceAccountCreated retry = create("billing", List.of());
        assertNotEquals(null, retry.credentials().clientSecret());
    }

    @Test
    void deleteRemovesTheClientAndItsUser() {
        ServiceAccountCreated created = create("billing", List.of());

        serviceAccountService.delete(realm(), ORG, "billing");

        assertTrue(testRealm.resource().clients().findByClientId("org-acme.billing").isEmpty());
        assertTrue(testRealm.resource().users().search("service-account-org-acme.billing", true).isEmpty());
        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> serviceAccountService.get(realm(), ORG, "billing"));
        assertNotNull(created.serviceAccount().userId());
    }

    private ServiceAccountCreated create(String name, List<String> defaultCatalogs) {
        return serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest(name, null, defaultCatalogs));
    }

    private ClientRepresentation client(String clientId) {
        return testRealm.resource().clients().findByClientId(clientId).getFirst();
    }

    private static Set<String> names(List<ClientScopeRepresentation> scopes) {
        return scopes.stream().map(ClientScopeRepresentation::getName).collect(Collectors.toSet());
    }

    private String realm() {
        return testRealm.name();
    }
}
