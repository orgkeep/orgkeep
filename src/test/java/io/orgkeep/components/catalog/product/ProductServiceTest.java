package io.orgkeep.components.catalog.product;

import io.orgkeep.api.CatalogRoleService;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.exceptions.CatalogNotFoundException;
import io.orgkeep.api.exceptions.FeatureNotFoundException;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.OrgNotFoundException;
import io.orgkeep.api.exceptions.ProductNotAssignedException;
import io.orgkeep.api.exceptions.ProductNotFoundException;
import io.orgkeep.api.exceptions.RoleNameTakenException;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateCatalogRoleRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.UpdateFeatureRequest;
import io.orgkeep.api.model.UpdateProductRequest;
import io.orgkeep.fixtures.TestRealm;
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
    private static final String ORG = "acme";
    private static final ParamDefinition REGION = new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us"));

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
                List.of(new Feature("flow", null), new Feature("scan", "Scan engine")), List.of(REGION), 0), product);
        Set<String> roles = catalogRoles().stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan", PRODUCT + ":flow"), roles);
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan", PRODUCT + ":flow"), rolesInScope());
        assertEquals(Set.of("prod.shop-repository.quotas", "prod.shop-repository.params"), mapperNames());
        // no JSON in the role attributes: one short value per element
        RoleRepresentation role = realm().clients().get(catalogUuid()).roles().get(PRODUCT).toRepresentation();
        assertEquals(List.of("enum"), role.getAttributes().get("param.region.type"));
        assertEquals(List.of("eu", "us"), role.getAttributes().get("param.region.values"));
    }

    // products and catalog roles share the role names of the client
    @Test
    void createWithANameTakenByACatalogRole() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("scanner", null, null, true));

        assertThrowsExactly(RoleNameTakenException.class, () -> productService.create(realmName(), CATALOG,
                new CreateProductRequest("scanner", "Scanner", null)));
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
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("scan"), Map.of()));

        GroupRepresentation group = realm().getGroupByPath("/" + ORG + "/catalogs/" + CATALOG + "/" + PRODUCT);
        // the default of region is applied
        assertEquals(new OrgProduct(CATALOG, PRODUCT, group.getId(), 1000L, QuotaCycle.MONTHLY, List.of("scan"),
                Map.of("region", "eu")), assigned);
        assertEquals(Map.of("quota", List.of("1000"), "quota-cycle", List.of("monthly"),
                "bought-features", List.of("scan"), "param.region", List.of("eu")), group.getAttributes());
        assertEquals(Set.of(PRODUCT), groupRoles(group.getId()));
        assertEquals(List.of(assigned), productService.listAssigned(realmName(), ORG));
    }

    @Test
    void assignAgainReplacesTheValues() {
        createProduct();
        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("scan"), Map.of()));

        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(20L, QuotaCycle.DAILY, List.of("flow", "scan"), Map.of("region", "us")));

        List<OrgProduct> assigned = productService.listAssigned(realmName(), ORG);
        assertEquals(1, assigned.size());
        assertEquals(20L, assigned.get(0).quota());
        assertEquals(List.of("flow", "scan"), assigned.get(0).boughtFeatures());
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
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("scan", "flow"), Map.of()));

        Product product = productService.deleteFeature(realmName(), CATALOG, PRODUCT, "flow");

        assertEquals(List.of(new Feature("scan", "Scan engine")), product.features());
        assertEquals(List.of("scan"), productService.listAssigned(realmName(), ORG).get(0).boughtFeatures());
        assertThrowsExactly(FeatureNotFoundException.class, () -> productService.deleteFeature(realmName(), CATALOG, PRODUCT, "flow"));
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
                List.of(new Feature("scan", "Scan engine"), new Feature("flow", null)), List.of(REGION)));
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
