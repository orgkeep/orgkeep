package org.orgkeep.components.catalog.role;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.exceptions.CatalogRoleNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.RoleNameTakenException;
import org.orgkeep.api.exceptions.UserNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CatalogRole;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.RoleHolder;
import org.orgkeep.api.model.UpdateCatalogRoleRequest;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.AccessToken;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog roles against the Keycloak of Dev Services, in a throwaway realm per test, down to the access token.
 */
@QuarkusTest
class CatalogRoleServiceTest {
    private static final String CATALOG = "prod";
    private static final String ORG = "acme";

    @Inject Keycloak keycloak;
    @Inject CatalogRoleService catalogRoleService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject RealmProvisioner realmProvisioner;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        // the login client, with fullScopeAllowed=false, as for the realm of the app
        realmProvisioner.ensureDefaults(testRealm.name());
        catalogService.create(testRealm.name(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void createListUpdateDelete() {
        catalogRoleService.create(testRealm.name(), CATALOG, new CreateCatalogRoleRequest("stats", "Statistics", null, true));
        catalogRoleService.create(testRealm.name(), CATALOG, new CreateCatalogRoleRequest("anonymous", null, "Test accounts", false));
        // a product of the same client is not a catalog role
        productService.create(testRealm.name(), CATALOG, new CreateProductRequest("billing", "Billing", null));

        assertEquals(List.of(
                        new CatalogRole(CATALOG, "anonymous", null, "Test accounts", false),
                        new CatalogRole(CATALOG, "stats", "Statistics", null, true)),
                catalogRoleService.list(testRealm.name(), CATALOG));
        assertThrowsExactly(CatalogRoleNotFoundException.class, () -> catalogRoleService.get(testRealm.name(), CATALOG, "billing"));
        assertThrowsExactly(RoleNameTakenException.class, () -> catalogRoleService.create(testRealm.name(), CATALOG,
                new CreateCatalogRoleRequest("billing", null, null, true)));

        CatalogRole updated = catalogRoleService.update(testRealm.name(), CATALOG, "stats",
                new UpdateCatalogRoleRequest(null, "See the stats", false));
        assertEquals(new CatalogRole(CATALOG, "stats", null, "See the stats", false), updated);
        assertEquals(updated, catalogRoleService.get(testRealm.name(), CATALOG, "stats"));

        catalogRoleService.delete(testRealm.name(), CATALOG, "stats");
        assertEquals(List.of("anonymous"), catalogRoleService.list(testRealm.name(), CATALOG).stream().map(CatalogRole::name).toList());
    }

    // granted directly by the realm owner: in resource_access.prod only when the token has the scope of the catalog
    @Test
    void grantedRoleReachesTheTokenWithTheScopeOfTheCatalog() {
        catalogRoleService.create(testRealm.name(), CATALOG, new CreateCatalogRoleRequest("anonymous", null, null, false));
        OrgMember member = memberService.add(testRealm.name(), ORG, new AddMemberRequest("bot@acme.test", "Test", "Bot"));

        catalogRoleService.grant(testRealm.name(), CATALOG, "anonymous", member.id());
        catalogRoleService.grant(testRealm.name(), CATALOG, "anonymous", member.id());

        assertEquals(List.of(new RoleHolder(member.id(), member.username(), "bot@acme.test")),
                catalogRoleService.holders(testRealm.name(), CATALOG, "anonymous"));
        AccessToken withScope = token(member.id(), "openid " + CATALOG);
        assertEquals(Set.of("anonymous"), withScope.getResourceAccess(CATALOG).getRoles());
        assertTrue(Set.of(withScope.getAudience()).contains(CATALOG));
        AccessToken withoutScope = token(member.id(), "openid");
        assertNull(withoutScope.getResourceAccess(CATALOG));
        // the org roles stay in the token, whatever the scope: the core API reads them
        assertTrue(withoutScope.getResourceAccess("org-" + ORG).getRoles().contains("org-user"));

        catalogRoleService.revoke(testRealm.name(), CATALOG, "anonymous", member.id());
        assertEquals(List.of(), catalogRoleService.holders(testRealm.name(), CATALOG, "anonymous"));
        assertNull(token(member.id(), "openid " + CATALOG).getResourceAccess(CATALOG));
    }

    // the catalog roles are for the users of the orgs, not for anyone in the realm
    @Test
    void grantToAUserOutsideTheOrgsIsRefused() {
        catalogRoleService.create(testRealm.name(), CATALOG, new CreateCatalogRoleRequest("anonymous", null, null, false));
        String outsider = testRealm.createUser("outsider");

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> catalogRoleService.grant(testRealm.name(), CATALOG, "anonymous", outsider));
        assertThrowsExactly(UserNotFoundException.class,
                () -> catalogRoleService.grant(testRealm.name(), CATALOG, "anonymous", "unknown-user-id"));
        assertFalse(catalogRoleService.holders(testRealm.name(), CATALOG, "anonymous").stream()
                .anyMatch(holder -> holder.userId().equals(outsider)));
    }

    /** The "evaluate" endpoint of the admin console: the token the login client would get, without any login. */
    private AccessToken token(String userId, String scope) {
        String loginClientUuid = testRealm.resource().clients().findByClientId(testRealm.name()).get(0).getId();
        return testRealm.resource().clients().get(loginClientUuid).clientScopesEvaluate()
                .generateAccessToken(scope, userId, null);
    }
}
