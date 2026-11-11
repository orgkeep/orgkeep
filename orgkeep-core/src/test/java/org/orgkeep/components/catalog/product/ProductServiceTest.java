package org.orgkeep.components.catalog.product;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.exceptions.CatalogNotFoundException;
import org.orgkeep.api.exceptions.FeatureNotFoundException;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ProductNotAssignedException;
import org.orgkeep.api.exceptions.ProductNotFoundException;
import org.orgkeep.api.exceptions.RoleNameTakenException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.api.model.ParamDefinition;
import org.orgkeep.api.model.ParamType;
import org.orgkeep.api.model.Product;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.UpdateFeatureRequest;
import org.orgkeep.api.model.UpdateProductRequest;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the Keycloak of Dev Services, in a throwaway realm per test:
 * the assertions read Keycloak directly to check what the service wrote there.
 */
@QuarkusTest
class ProductServiceTest {
    private static final String CATALOG = "prod";
    private static final String PRODUCT = "shop-repository";
    private static final String BILLING = "billing";
    private static final String ORG = "acme";
    private static final ParamDefinition REGION = new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us"));
    private static final FeatureQuota DAILY_200 = new FeatureQuota(200L, QuotaCycle.DAILY);

    @Inject Keycloak keycloak;
    @Inject ProductService productService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject OrgService orgService;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        catalogService.create(realmName(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(realmName(), new CreateOrgRequest(ORG, Set.of(), null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // the product and each of its features are real client roles, mapped on the scope of the catalog,
    // and the product has its two claim mappers on that scope
    @Test
    void createStoresRolesScopeMappingsAndMappers() {
        Product product = createProduct();

        assertEquals(new Product(CATALOG, PRODUCT, "Shop repository", "Repositories",
                List.of(new Feature("dig", null), new Feature("search", "Search engine")), false, List.of(REGION), 0), product);
        Set<String> roles = catalogRoles().stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertEquals(Set.of(PRODUCT, PRODUCT + ":search", PRODUCT + ":dig"), roles);
        assertEquals(Set.of(PRODUCT, PRODUCT + ":search", PRODUCT + ":dig"), rolesInScope());
        assertEquals(Set.of("prod.shop-repository.quotas", "prod.shop-repository.params"), mapperNames());
        // no JSON in the role attributes: one short value per element
        RoleRepresentation role = realm().clients().get(catalogUuid()).roles().get(PRODUCT).toRepresentation();
        assertEquals(List.of("enum"), role.getAttributes().get("param.region.type"));
        assertEquals(List.of("eu", "us"), role.getAttributes().get("param.region.values"));
    }

    // products and catalog roles share the role names of the client
    @Test
    void createWithANameTakenByACatalogRole() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("searcher", null, null, true));

        assertThrowsExactly(RoleNameTakenException.class, () -> productService.create(realmName(), CATALOG,
                new CreateProductRequest("searcher", "Searcher", null)));
        assertThrowsExactly(CatalogNotFoundException.class, () -> productService.create(realmName(), "unknown",
                new CreateProductRequest("billing", "Billing", null)));
    }

    @Test
    void listCountsTheOrgsOfEachProduct() {
        createProduct();
        productService.create(realmName(), CATALOG, new CreateProductRequest("billing", "Billing", null));
        productService.assign(realmName(), ORG, CATALOG, "billing", new AssignProductRequest(10L, QuotaCycle.DAILY));

        List<Product> products = productService.list(realmName(), CATALOG);

        assertEquals(List.of("billing", PRODUCT), products.stream().map(Product::name).toList());
        assertEquals(List.of(1, 0), products.stream().map(Product::orgCount).toList());
    }

    @Test
    void assignStoresWhatTheOrgNegotiated() {
        createProduct();

        OrgProduct assigned = productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("search"), Map.of()));

        GroupRepresentation group = realm().getGroupByPath("/" + ORG + "/catalogs/" + CATALOG + "/" + PRODUCT);
        // the default of region is applied
        assertEquals(new OrgProduct(CATALOG, PRODUCT, group.getId(), 1000L, QuotaCycle.MONTHLY, List.of("search"),
                Map.of(), Map.of("region", "eu")), assigned);
        assertEquals(Map.of("quota", List.of("1000"), "quota-cycle", List.of("monthly"),
                "bought-features", List.of("search"), "param.region", List.of("eu")), group.getAttributes());
        assertEquals(Set.of(PRODUCT), groupRoles(group.getId()));
        assertEquals(List.of(assigned), productService.listAssigned(realmName(), ORG));
    }

    @Test
    void assignAgainReplacesTheValues() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("search"), Map.of()));

        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(20L, QuotaCycle.DAILY, List.of("dig", "search"), Map.of("region", "us")));

        List<OrgProduct> assigned = productService.listAssigned(realmName(), ORG);
        assertEquals(1, assigned.size());
        assertEquals(20L, assigned.get(0).quota());
        assertEquals(List.of("dig", "search"), assigned.get(0).boughtFeatures());
        assertEquals(Map.of("region", "us"), assigned.get(0).params());
    }

    @Test
    void assignErrors() {
        createProduct();

        assertThrowsExactly(InvalidRequestException.class, () -> productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("export"), Map.of())));
        assertThrowsExactly(InvalidRequestException.class, () -> productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of(), Map.of("region", "asia"))));
        assertThrowsExactly(ProductNotFoundException.class, () -> productService.assign(realmName(), ORG, CATALOG, "unknown",
                new AssignProductRequest(10L, QuotaCycle.DAILY)));
        assertThrowsExactly(OrgNotFoundException.class, () -> productService.assign(realmName(), "unknown", CATALOG, PRODUCT,
                new AssignProductRequest(10L, QuotaCycle.DAILY)));
        // a simple product: a quota of product and its cycle, never quotas per feature
        assertThrowsExactly(InvalidRequestException.class, () -> productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(10L, null)));
        assertThrowsExactly(InvalidRequestException.class, () -> productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("search"), Map.of("search", DAILY_200), Map.of())));
    }

    // a metered product: no quota of product, a quota per metered feature bought, read back as it was written
    @Test
    void assignAMeteredProductStoresAQuotaPerFeature() {
        createMeteredProduct();

        OrgProduct assigned = productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(
                List.of("stats", "search", "dig"), Map.of("search", DAILY_200, "dig", new FeatureQuota(100L, QuotaCycle.MONTHLY))));

        GroupRepresentation group = realm().getGroupByPath("/" + ORG + "/catalogs/" + CATALOG + "/" + BILLING);
        assertEquals(new OrgProduct(CATALOG, BILLING, group.getId(), null, null, List.of("dig", "search", "stats"),
                Map.of("dig", new FeatureQuota(100L, QuotaCycle.MONTHLY), "search", DAILY_200), Map.of()), assigned);
        assertEquals(Map.of("bought-features", List.of("dig", "search", "stats"),
                "quota.search", List.of("200"), "quota-cycle.search", List.of("daily"),
                "quota.dig", List.of("100"), "quota-cycle.dig", List.of("monthly")), group.getAttributes());
        // the product role stays on the assignment group: it counts and filters the orgs
        assertEquals(Set.of(BILLING), groupRoles(group.getId()));
        assertEquals(List.of(assigned), productService.listAssigned(realmName(), ORG));
    }

    // a renegotiation replaces everything: a metered feature no longer bought leaves with its quota
    @Test
    void assignAMeteredProductAgainReplacesTheQuotas() {
        createMeteredProduct();
        productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(
                List.of("search", "dig"), Map.of("search", DAILY_200, "dig", DAILY_200)));

        productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(
                List.of("search"), Map.of("search", new FeatureQuota(50L, QuotaCycle.YEARLY))));

        OrgProduct assigned = productService.listAssigned(realmName(), ORG).get(0);
        assertEquals(List.of("search"), assigned.boughtFeatures());
        assertEquals(Map.of("search", new FeatureQuota(50L, QuotaCycle.YEARLY)), assigned.featureQuotas());
    }

    // the quotas follow the kind of the product: each error names what is wrong, and nothing is written
    @Test
    void assignAMeteredProductErrors() {
        createMeteredProduct();

        // no quota of product
        assertInvalid("quota", new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("search"), Map.of("search", DAILY_200), Map.of()));
        // at least one feature bought: without an access role, the product would give nothing
        assertInvalid("at least one", AssignProductRequest.metered(List.of(), Map.of()));
        // a metered feature bought without its quota
        assertInvalid("[dig]", AssignProductRequest.metered(List.of("search", "dig"), Map.of("search", DAILY_200)));
        // a quota for a metered feature not bought, or for a feature that is not metered
        assertInvalid("[dig]", AssignProductRequest.metered(List.of("search"), Map.of("search", DAILY_200, "dig", DAILY_200)));
        assertInvalid("[stats]", AssignProductRequest.metered(List.of("search", "stats"), Map.of("search", DAILY_200, "stats", DAILY_200)));

        assertEquals(List.of(), productService.listAssigned(realmName(), ORG));
    }

    // an org may buy only the features without quota of a metered product (permissions): then no quota at all
    @Test
    void aMeteredProductBoughtWithoutMeteredFeature() {
        createMeteredProduct();

        OrgProduct assigned = productService.assign(realmName(), ORG, CATALOG, BILLING,
                AssignProductRequest.metered(List.of("stats"), Map.of()));

        assertEquals(List.of("stats"), assigned.boughtFeatures());
        assertEquals(Map.of(), assigned.featureQuotas());
    }

    @Test
    void unassignDeletesTheAssignmentGroup() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT, new AssignProductRequest(10L, QuotaCycle.DAILY));

        productService.unassign(realmName(), ORG, CATALOG, PRODUCT);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> realm().getGroupByPath("/" + ORG + "/catalogs/" + CATALOG + "/" + PRODUCT));
        assertEquals(404, e.getResponse().getStatus());
        assertThrowsExactly(ProductNotAssignedException.class, () -> productService.unassign(realmName(), ORG, CATALOG, PRODUCT));
    }

    @Test
    void deleteAssignedProductIsRefused() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT, new AssignProductRequest(10L, QuotaCycle.DAILY));

        assertThrowsExactly(OperationNotAllowedException.class, () -> productService.delete(realmName(), CATALOG, PRODUCT));

        productService.unassign(realmName(), ORG, CATALOG, PRODUCT);
        productService.delete(realmName(), CATALOG, PRODUCT);
        // its features and mappers go with it
        assertEquals(List.of(), catalogRoles());
        assertEquals(Set.of(), mapperNames());
    }

    // the orgs that bought a removed feature lose it
    @Test
    void deleteFeatureRemovesItFromTheAssignments() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("search", "dig"), Map.of()));

        Product product = productService.deleteFeature(realmName(), CATALOG, PRODUCT, "dig");

        assertEquals(List.of(new Feature("search", "Search engine")), product.features());
        assertEquals(List.of("search"), productService.listAssigned(realmName(), ORG).get(0).boughtFeatures());
        assertThrowsExactly(FeatureNotFoundException.class, () -> productService.deleteFeature(realmName(), CATALOG, PRODUCT, "dig"));
    }

    // metered is read at creation only: renaming the feature keeps it
    @Test
    void putFeatureCreatesThenUpdates() {
        createProduct();

        productService.putFeature(realmName(), CATALOG, PRODUCT, "export", new UpdateFeatureRequest(null, true));
        Product product = productService.putFeature(realmName(), CATALOG, PRODUCT, "export", new UpdateFeatureRequest("Export"));

        assertTrue(product.features().contains(new Feature("export", "Export", true)));
        RoleRepresentation role = realm().clients().get(catalogUuid()).roles().get(PRODUCT + ":export").toRepresentation();
        assertEquals(List.of("true"), role.getAttributes().get("metered"));
        assertTrue(rolesInScope().contains(PRODUCT + ":export"));
        assertThrowsExactly(InvalidRequestException.class,
                () -> productService.putFeature(realmName(), CATALOG, PRODUCT, "Not_A_Slug", new UpdateFeatureRequest(null)));
    }

    // the kind of a product is derived from its features: one metered feature makes it metered.
    // Free on a product nobody has, as is removing its last metered feature
    @Test
    void aMeteredFeatureMakesTheProductMetered() {
        createProduct();

        Product product = productService.putFeature(realmName(), CATALOG, PRODUCT, "engine", new UpdateFeatureRequest(null, true));
        assertTrue(product.metered());
        // the metered feature gets the mapper of its quotas, and loses it with the feature
        assertTrue(mapperNames().contains("prod.shop-repository.engine.quotas"));

        product = productService.deleteFeature(realmName(), CATALOG, PRODUCT, "engine");
        assertFalse(product.metered());
        assertEquals(Set.of("prod.shop-repository.quotas", "prod.shop-repository.params"), mapperNames());
    }

    // each metered feature has the mapper of its quotas, {"billing": {"search": {"quotas": [...]}}}; a feature that is
    // not metered has none, its role is enough. All go with the product
    @Test
    void aMeteredProductHasAMapperPerMeteredFeature() {
        createMeteredProduct();

        assertEquals(Set.of("prod.billing.quotas", "prod.billing.params", "prod.billing.search.quotas",
                "prod.billing.dig.quotas"), mapperNames());

        productService.delete(realmName(), CATALOG, BILLING);
        assertEquals(Set.of(), mapperNames());
    }

    // the object of a metered feature sits next to the keys of its product in the token: a feature never takes one of
    // their names
    @Test
    void reservedFeatureNamesAreRefused() {
        assertThrowsExactly(InvalidRequestException.class, () -> productService.create(realmName(), CATALOG,
                new CreateProductRequest("reports", "Reports", null, List.of(new Feature("params", null)), List.of())));
        createProduct();

        for (String reserved : List.of("params", "options", "quotas")) {
            assertThrowsExactly(InvalidRequestException.class, () -> productService.putFeature(realmName(), CATALOG,
                    PRODUCT, reserved, new UpdateFeatureRequest(null, true)));
        }
        assertFalse(catalogRoles().stream().anyMatch(role -> "reports".equals(role.getName())));
    }

    // metered is set at creation: the same value is accepted (a rename), another one is refused
    @Test
    void meteredNeverChangesOnAnExistingFeature() {
        createMeteredProduct();

        productService.putFeature(realmName(), CATALOG, BILLING, "search", new UpdateFeatureRequest("Search", true));
        productService.putFeature(realmName(), CATALOG, BILLING, "stats", new UpdateFeatureRequest("Stats", false));

        assertThrowsExactly(OperationNotAllowedException.class, () -> productService.putFeature(realmName(), CATALOG,
                BILLING, "search", new UpdateFeatureRequest(null, false)));
        assertThrowsExactly(OperationNotAllowedException.class, () -> productService.putFeature(realmName(), CATALOG,
                BILLING, "stats", new UpdateFeatureRequest(null, true)));
        assertEquals(List.of(new Feature("dig", "Dig engine", true), new Feature("search", "Search", true),
                new Feature("stats", "Stats", false)), productService.get(realmName(), CATALOG, BILLING).features());
    }

    // an assigned simple product keeps its kind: a first metered feature would change the quotas of its orgs.
    // A feature without quota is still free
    @Test
    void theFirstMeteredFeatureOfAnAssignedProductIsRefused() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT, new AssignProductRequest(10L, QuotaCycle.DAILY));

        assertThrowsExactly(OperationNotAllowedException.class, () -> productService.putFeature(realmName(), CATALOG,
                PRODUCT, "engine", new UpdateFeatureRequest(null, true)));

        Product product = productService.putFeature(realmName(), CATALOG, PRODUCT, "export", new UpdateFeatureRequest(null));
        assertFalse(product.metered());
        assertFalse(catalogRoles().stream().anyMatch(role -> role.getName().equals(PRODUCT + ":engine")));
    }

    // an assigned metered product can get more metered features: no org has bought them yet
    @Test
    void aNewMeteredFeatureOnAnAssignedMeteredProductIsFree() {
        createMeteredProduct();
        productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(List.of("search"), Map.of("search", DAILY_200)));

        Product product = productService.putFeature(realmName(), CATALOG, BILLING, "search", new UpdateFeatureRequest(null, true));

        assertTrue(product.features().contains(new Feature("search", null, true)));
    }

    // a metered feature an org bought carries a negotiated quota: refused, the orgs that bought it named.
    // One nobody bought goes, as long as another metered feature remains
    @Test
    void aBoughtMeteredFeatureCannotBeDeleted() {
        createMeteredProduct();
        productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(List.of("search"), Map.of("search", DAILY_200)));

        OperationNotAllowedException e = assertThrowsExactly(OperationNotAllowedException.class,
                () -> productService.deleteFeature(realmName(), CATALOG, BILLING, "search"));
        assertTrue(e.getMessage().contains(ORG), e.getMessage());

        Product product = productService.deleteFeature(realmName(), CATALOG, BILLING, "dig");
        assertEquals(List.of("search", "stats"), product.features().stream().map(Feature::name).toList());
    }

    // the last metered feature of an assigned product: refused even if nobody bought it (the kind would change).
    // A feature without quota goes as before, and leaves the purchases of the orgs
    @Test
    void theLastMeteredFeatureOfAnAssignedProductIsRefused() {
        productService.create(realmName(), CATALOG, new CreateProductRequest(BILLING, "Billing", null,
                List.of(new Feature("stats", null), new Feature("search", null, true)), List.of()));
        productService.assign(realmName(), ORG, CATALOG, BILLING, AssignProductRequest.metered(List.of("stats"), Map.of()));

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> productService.deleteFeature(realmName(), CATALOG, BILLING, "search"));

        productService.deleteFeature(realmName(), CATALOG, BILLING, "stats");
        assertEquals(List.of(), productService.listAssigned(realmName(), ORG).get(0).boughtFeatures());
    }

    // the values of the orgs follow the schema: a removed parameter goes, a new one gets its default
    @Test
    void updateFitsTheValuesOfTheOrgsToTheNewSchema() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT, new AssignProductRequest(10L, QuotaCycle.DAILY));
        ParamDefinition tier = new ParamDefinition("tier", ParamType.STRING, true, "gold", List.of());

        Product product = productService.update(realmName(), CATALOG, PRODUCT,
                new UpdateProductRequest("Shop", null, List.of(tier)));

        assertEquals(List.of(tier), product.params());
        assertEquals("Shop", product.displayName());
        OrgProduct assigned = productService.listAssigned(realmName(), ORG).get(0);
        assertEquals(Map.of("tier", "gold"), assigned.params());
        // what is not a parameter is kept
        assertEquals(10L, assigned.quota());
    }

    // a new required parameter without default: the orgs would have no value, refused before anything changes
    @Test
    void updateRefusesARequiredParameterWithoutDefault() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT, new AssignProductRequest(10L, QuotaCycle.DAILY));
        ParamDefinition tier = new ParamDefinition("tier", ParamType.STRING, true, null, List.of());

        InvalidRequestException e = assertThrowsExactly(InvalidRequestException.class, () -> productService.update(
                realmName(), CATALOG, PRODUCT, new UpdateProductRequest("Shop", null, List.of(REGION, tier))));

        assertTrue(e.getMessage().contains(ORG), e.getMessage());
        assertEquals(List.of(REGION), productService.get(realmName(), CATALOG, PRODUCT).params());
    }

    private Product createProduct() {
        return productService.create(realmName(), CATALOG, new CreateProductRequest(PRODUCT, "Shop repository", "Repositories",
                List.of(new Feature("search", "Search engine"), new Feature("dig", null)), List.of(REGION)));
    }

    /** The assignment of the metered product is refused, with a message that contains {@code expected}. */
    private void assertInvalid(String expected, AssignProductRequest request) {
        InvalidRequestException e = assertThrowsExactly(InvalidRequestException.class,
                () -> productService.assign(realmName(), ORG, CATALOG, BILLING, request));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    /** A metered product: a permission (stats) and two engines bought with a quota each (search, dig). */
    private Product createMeteredProduct() {
        return productService.create(realmName(), CATALOG, new CreateProductRequest(BILLING, "Billing", null,
                List.of(new Feature("stats", null), new Feature("search", "Search engine", true),
                        new Feature("dig", "Dig engine", true)), List.of()));
    }

    private String realmName() {
        return testRealm.name();
    }

    private RealmResource realm() {
        return testRealm.resource();
    }

    private String catalogUuid() {
        return realm().clients().findByClientId(CATALOG).get(0).getId();
    }

    private List<RoleRepresentation> catalogRoles() {
        return realm().clients().get(catalogUuid()).roles().list();
    }

    private String scopeId() {
        return realm().clientScopes().findAll().stream()
                .filter(scope -> CATALOG.equals(scope.getName()))
                .map(ClientScopeRepresentation::getId)
                .findFirst().orElseThrow();
    }

    private Set<String> rolesInScope() {
        return realm().clientScopes().get(scopeId()).getScopeMappings().clientLevel(catalogUuid()).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> mapperNames() {
        return realm().clientScopes().get(scopeId()).getProtocolMappers().getMappers().stream()
                .map(ProtocolMapperRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> groupRoles(String groupId) {
        return realm().groups().group(groupId).roles().clientLevel(catalogUuid()).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }
}
