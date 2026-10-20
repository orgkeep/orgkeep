package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.core.CatalogsClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.when;

/**
 * /api/products gathers the products of every catalog of the core: the catalogs client is a Mockito mock
 * (see OrganisationsResourceTest for @InjectMock).
 */
@QuarkusTest
class ProductsResourceTest {

    @InjectMock
    @RestClient
    CatalogsClient catalogsClient;

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void listsTheProductsOfEveryCatalog() {
        when(catalogsClient.list()).thenReturn(List.of(
                new Catalog("prod", "Production", null, 1),
                new Catalog("sandbox", "Sandbox", null, 1)));
        when(catalogsClient.products("prod")).thenReturn(List.of(product("prod", "billing")));
        when(catalogsClient.products("sandbox")).thenReturn(List.of(product("sandbox", "billing")));

        // the same product name in two catalogs: two products, told apart by their catalog
        given().when().get("/api/products")
                .then().statusCode(200)
                .body("catalog", contains("prod", "sandbox"))
                .body("name", contains("billing", "billing"));
    }

    private static Product product(String catalog, String name) {
        return new Product(catalog, name, "Billing", null, List.of(), List.of(), 0);
    }
}
