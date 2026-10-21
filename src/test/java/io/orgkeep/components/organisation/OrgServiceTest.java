package io.orgkeep.components.organisation;

import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.exceptions.MemberOfAnotherOrgException;
import io.orgkeep.api.exceptions.OrgAlreadyExistsException;
import io.orgkeep.api.exceptions.OrgNotFoundException;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateOrgResponse;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.OrgClients;
import io.orgkeep.api.model.OrgDetails;
import io.orgkeep.api.model.OrgMember;
import io.orgkeep.api.model.OrgRole;
import io.orgkeep.api.model.OrgSummary;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class OrgServiceTest {
    private static final String ORG = "it-demo-org";
    private static final String OWNER_EMAIL = "owner@it-demo-org.test";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject ProductService productService;
    @Inject CatalogService catalogService;

    private TestRealm testRealm;

    @BeforeEach
    public void beforeEach(){
        testRealm = TestRealm.create(keycloak);
    }

    @AfterEach
    public void afterEach(){
        testRealm.close();
    }

    @Test
    public void createOrganisation(){
        CreateOrgResponse response = orgService.create(testRealm.name(), request(null));

        List<OrganizationRepresentation> orgs = realm().organizations().search(ORG);
        assertEquals(1,orgs.size());

        // the response carries the ids of everything that was created
        assertEquals(orgs.get(0).getId(), response.id());
        assertEquals(ORG, response.name());
        assertEquals("org-" + ORG, response.clientId());
        assertEquals(orgClient().getId(), response.clientUuid());
        assertEquals(realm().getGroupByPath("/" + ORG).getId(), response.groupId());
    }

    @Test
    public void createAlreadyExisting(){
        orgService.create(testRealm.name(), request(null));
        assertThrowsExactly(OrgAlreadyExistsException.class,() -> orgService.create(testRealm.name(), request(null)));
    }

    @Test
    public void createsOrgClientWithOrgRoles(){
        orgService.create(testRealm.name(), request(null));

        ClientRepresentation client = orgClient();
        Set<String> roles = realm().clients().get(client.getId()).roles().list().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        Set<String> expected = Arrays.stream(OrgRole.values())
                .map(OrgRole::getRoleName)
                .collect(Collectors.toSet());
        assertEquals(expected, roles);
    }

    @Test
    public void createsOrgGroupsWithOrgRoles(){
        orgService.create(testRealm.name(), request(null));

        // getGroupByPath throws a 404 WebApplicationException if a group is missing
        realm().getGroupByPath("/" + ORG + "/catalogs");
        assertEquals(Set.of(OrgRole.ORG_ADMIN.getRoleName()), groupClientRoles("/" + ORG + "/user-groups/admin"));
        assertEquals(Set.of(OrgRole.ORG_USER.getRoleName()), groupClientRoles("/" + ORG + "/user-groups/users"));
    }

    @Test
    public void createsOwnerAsMemberWithOrgOwnerRole(){
        CreateOrgResponse response = orgService.create(testRealm.name(), request("changeme"));

        List<UserRepresentation> users = realm().users().searchByEmail(OWNER_EMAIL, true);
        assertEquals(1, users.size());
        assertEquals(users.get(0).getId(), response.ownerId());
        assertOwner(users.get(0).getId());
    }

    @Test
    public void reusesExistingUserAsOwner(){
        String userId = testRealm.createUser("bob", OWNER_EMAIL);

        CreateOrgResponse response = orgService.create(testRealm.name(), request(null));

        assertEquals(1, realm().users().searchByEmail(OWNER_EMAIL, true).size());
        assertEquals(userId, response.ownerId());
        assertOwner(userId);
    }

    @Test
    public void ownerOfAnotherOrgIsRefused(){
        orgService.create(testRealm.name(), request("changeme"));

        assertThrowsExactly(MemberOfAnotherOrgException.class, () -> orgService.create(testRealm.name(),
                new CreateOrgRequest("other-org", Set.of(), OWNER_EMAIL, null)));
        // checked before anything is created
        assertTrue(realm().organizations().search("other-org").isEmpty());
    }

    @Test
    public void deleteAlsoRemovesOrgClientAndGroups(){
        CreateOrgResponse response = orgService.create(testRealm.name(), request(null));

        orgService.delete(testRealm.name(), response.name());

        assertTrue(realm().clients().findByClientId(OrgClients.clientId(ORG)).isEmpty());
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> realm().getGroupByPath("/" + ORG));
        assertEquals(404, e.getResponse().getStatus());
    }

    @Test
    public void listReturnsOrgSummaries(){
        CreateOrgResponse acme = orgService.create(testRealm.name(), request(null));
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of("other-org.test"), null, null));

        List<OrgSummary> all = orgService.list(testRealm.name(), null, null, null, 0, 100);
        assertEquals(Set.of(ORG, "other-org"), all.stream().map(OrgSummary::name).collect(Collectors.toSet()));

        OrgSummary summary = all.stream().filter(org -> org.id().equals(acme.id())).findFirst().orElseThrow();
        assertEquals(Set.of("it-demo-org.test"), summary.emailDomains());
        assertTrue(summary.enabled());
    }

    // The owner, the member count and the products are read from three different places in Keycloak
    // (a client role, the organisation, a group tree): one test builds an org that has all three.
    @Test
    public void listSummarisesOwnersMembersAndProducts(){
        orgService.create(testRealm.name(), request(null));
        memberService.add(testRealm.name(), ORG, new AddMemberRequest("member@it-demo-org.test", "Mia", "Member"));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("storage", "Storage", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("billing", "Billing", null));
        productService.create(testRealm.name(), "sandbox", new CreateProductRequest("billing", "Billing", null));
        productService.assign(testRealm.name(), ORG, "prod", "storage", new AssignProductRequest(10L, QuotaCycle.DAILY));
        productService.assign(testRealm.name(), ORG, "sandbox", "billing", new AssignProductRequest(5L, QuotaCycle.DAILY));
        productService.assign(testRealm.name(), ORG, "prod", "billing", new AssignProductRequest(500L, QuotaCycle.MONTHLY));
        // without owner: empty lists and a count of zero, not nulls
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of(), null, null));

        List<OrgSummary> all = orgService.list(testRealm.name(), null, null, null, 0, 100);
        OrgSummary acme = all.stream().filter(org -> org.name().equals(ORG)).findFirst().orElseThrow();
        OrgSummary other = all.stream().filter(org -> org.name().equals("other-org")).findFirst().orElseThrow();

        assertEquals(List.of(OWNER_EMAIL), acme.owners().stream().map(OrgMember::email).toList());
        // the owner is a member too
        assertEquals(2, acme.memberCount());
        // sorted, whatever the order of assignment
        assertEquals(List.of("prod/billing", "prod/storage", "sandbox/billing"), acme.products());

        assertEquals(List.of(), other.owners());
        assertEquals(0, other.memberCount());
        assertEquals(List.of(), other.products());
    }

    @Test
    public void listFiltersOnSearch(){
        orgService.create(testRealm.name(), request(null));
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of("other-org.test"), null, null));

        List<OrgSummary> found = orgService.list(testRealm.name(), "other", null, null, 0, 100);
        assertEquals(List.of("other-org"), found.stream().map(OrgSummary::name).toList());
    }

    // Three orgs: billing assigned to two of them in prod, to the third in sandbox. The filter on a catalog and a
    // product, on one of them only, combined with the search, with a page, and on what nobody has or does not exist.
    @Test
    public void listFiltersOnCatalogAndProduct(){
        orgService.create(testRealm.name(), request(null));
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of(), null, null));
        orgService.create(testRealm.name(), new CreateOrgRequest("third-org", Set.of(), null, null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("billing", "Billing", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("storage", "Storage", null));
        productService.create(testRealm.name(), "sandbox", new CreateProductRequest("billing", "Billing", null));
        AssignProductRequest quota = new AssignProductRequest(100L, QuotaCycle.MONTHLY);
        productService.assign(testRealm.name(), ORG, "prod", "billing", quota);
        productService.assign(testRealm.name(), "third-org", "prod", "billing", quota);
        productService.assign(testRealm.name(), "other-org", "sandbox", "billing", quota);

        // sorted by name, like the list without filter
        assertEquals(List.of(ORG, "third-org"), names(orgService.list(testRealm.name(), null, "prod", "billing", 0, 100)));
        assertEquals(List.of("third-org"), names(orgService.list(testRealm.name(), "third", "prod", "billing", 0, 100)));
        assertEquals(List.of("third-org"), names(orgService.list(testRealm.name(), null, "prod", "billing", 1, 100)));
        // a catalog alone: any of its products. A product alone: in any catalog
        assertEquals(List.of("other-org"), names(orgService.list(testRealm.name(), null, "sandbox", null, 0, 100)));
        assertEquals(List.of(ORG, "other-org", "third-org"), names(orgService.list(testRealm.name(), null, null, "billing", 0, 100)));
        assertEquals(List.of(), names(orgService.list(testRealm.name(), null, "prod", "storage", 0, 100)));
        assertEquals(List.of(), names(orgService.list(testRealm.name(), null, "prod", "unknown", 0, 100)));
        assertEquals(List.of(), names(orgService.list(testRealm.name(), null, "unknown", null, 0, 100)));
    }

    private static List<String> names(List<OrgSummary> orgs) {
        return orgs.stream().map(OrgSummary::name).toList();
    }

    @Test
    public void getReturnsOrgDetails(){
        CreateOrgResponse created = orgService.create(testRealm.name(), request("changeme"));

        OrgDetails details = orgService.get(testRealm.name(), ORG);

        // get must find back what create produced
        assertEquals(created.id(), details.id());
        assertEquals(ORG, details.name());
        assertEquals(Set.of("it-demo-org.test"), details.emailDomains());
        assertEquals(created.clientId(), details.clientId());
        assertEquals(created.clientUuid(), details.clientUuid());
        assertEquals(created.groupId(), details.groupId());
        assertEquals(List.of(created.ownerId()), details.owners().stream().map(OrgMember::id).toList());
        assertEquals(OrgRole.ORG_OWNER, details.owners().get(0).role());
    }

    @Test
    public void getUnknownOrgThrowsNotFound(){
        assertThrowsExactly(OrgNotFoundException.class, () -> orgService.get(testRealm.name(), "unknown-org"));
    }

    @Test
    public void getMatchesExactName(){
        orgService.create(testRealm.name(), new CreateOrgRequest("acme-corp", Set.of("acme-corp.test"), null, null));

        // Keycloak search is a "contains": "acme" must not resolve to "acme-corp"
        assertThrowsExactly(OrgNotFoundException.class, () -> orgService.get(testRealm.name(), "acme"));
    }

    @Test
    public void deleteUnknownOrgThrowsNotFound(){
        assertThrowsExactly(OrgNotFoundException.class, () -> orgService.delete(testRealm.name(), "unknown-org"));
    }

    @Test
    public void orgRolesAreHierarchical(){
        orgService.create(testRealm.name(), request(null));

        assertEquals(Set.of(OrgRole.ORG_ADMIN.getRoleName()), composites(OrgRole.ORG_OWNER));
        assertEquals(Set.of(OrgRole.ORG_USER.getRoleName()), composites(OrgRole.ORG_ADMIN));
        assertEquals(Set.of(), composites(OrgRole.ORG_USER));
    }

    private Set<String> composites(OrgRole role) {
        return realm().clients().get(orgClient().getId()).roles().get(role.getRoleName()).getRoleComposites().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private void assertOwner(String userId) {
        Set<String> memberIds = realm().organizations().get(orgId()).members().getAll().stream()
                .map(MemberRepresentation::getId)
                .collect(Collectors.toSet());
        assertTrue(memberIds.contains(userId), "owner should be a member of the organisation");

        Set<String> clientRoles = realm().users().get(userId).roles().clientLevel(orgClient().getId()).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(OrgRole.ORG_OWNER.getRoleName()), clientRoles);
    }

    private Set<String> groupClientRoles(String path) {
        String groupId = realm().getGroupByPath(path).getId();
        return realm().groups().group(groupId).roles().clientLevel(orgClient().getId()).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private CreateOrgRequest request(String ownerPassword) {
        return new CreateOrgRequest(ORG, Set.of("it-demo-org.test"), OWNER_EMAIL, ownerPassword, "Olivia", "Owner");
    }

    private RealmResource realm() {
        return testRealm.resource();
    }

    private String orgId() {
        return realm().organizations().search(ORG).get(0).getId();
    }

    private ClientRepresentation orgClient() {
        List<ClientRepresentation> clients = realm().clients().findByClientId("org-" + ORG);
        assertEquals(1, clients.size());
        return clients.get(0);
    }
}
