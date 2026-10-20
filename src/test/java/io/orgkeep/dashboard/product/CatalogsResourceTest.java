package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.core.CatalogsClient;
import io.orgkeep.dashboard.core.CoreApiException;
import io.orgkeep.dashboard.product.Product.ParamDefinition;
import io.orgkeep.dashboard.product.Product.ProductRole;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /api/catalogs relays the catalogs and products endpoints of the core: the catalogs client is a Mockito mock
 * (see OrganisationsResourceTest for @InjectMock and @TestSecurity).
 */
@QuarkusTest
class CatalogsResourceTest {

    @InjectMock
    @RestClient
    CatalogsClient catalogsClient;

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void createsACatalog() {
        CreateCatalogRequest request = new CreateCatalogRequest("sandbox", "Sandbox", "To try the APIs");
        when(catalogsClient.create(request)).thenReturn(new Catalog("sandbox", "Sandbox", "To try the APIs", 0));

        given().contentType(ContentType.JSON).body(request)
                .when().post("/api/catalogs")
                .then().statusCode(201)
                .body("name", equalTo("sandbox"))
                .body("productCount", equalTo(0));

        // records have equals(): the core received exactly what the front sent
        verify(catalogsClient).create(request);
    }

    // the core refuses to delete a catalog that still has products: the front shows its message
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void relaysTheRefusalToDeleteACatalog() {
        // doThrow().when(mock): the way to stub a void method, when(mock.delete(...)) does not compile
        doThrow(new CoreApiException(409, "Catalog 'prod' still has products or roles"))
                .when(catalogsClient).delete("prod");

        given().when().delete("/api/catalogs/{catalog}", "prod")
                .then().statusCode(409)
                .body("message", equalTo("Catalog 'prod' still has products or roles"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void createsAProductWithItsRolesAndParameters() {
        CreateProductRequest request = new CreateProductRequest("billing", "Billing API", null,
                List.of(new ProductRole("read", "Read")),
                List.of(new ParamDefinition("region", "ENUM", true, "eu", List.of("eu", "us"))));
        when(catalogsClient.createProduct("prod", request)).thenReturn(new Product("prod", "billing", "Billing API",
                null, request.roles(), request.params(), 0));

        given().contentType(ContentType.JSON).body(request)
                .when().post("/api/catalogs/{catalog}/products", "prod")
                .then().statusCode(201)
                .body("roles[0].name", equalTo("read"))
                .body("params[0].values[1]", equalTo("us"));

        verify(catalogsClient).createProduct("prod", request);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void addsARoleToAProduct() {
        UpdateProductRoleRequest request = new UpdateProductRoleRequest("Write");
        when(catalogsClient.putProductRole("prod", "billing", "write", request)).thenReturn(new Product("prod",
                "billing", "Billing API", null, List.of(new ProductRole("write", "Write")), List.of(), 0));

        given().contentType(ContentType.JSON).body(request)
                .when().put("/api/catalogs/{catalog}/products/{product}/roles/{role}", "prod", "billing", "write")
                .then().statusCode(200)
                .body("roles[0].displayName", equalTo("Write"));
    }

    @Test
    @TestSecurity(user = "bob", roles = "org-owner")
    void refusesAUserWhoIsNotRealmOwner() {
        given().when().get("/api/catalogs")
                .then().statusCode(403);

        verifyNoInteractions(catalogsClient);
    }
}
