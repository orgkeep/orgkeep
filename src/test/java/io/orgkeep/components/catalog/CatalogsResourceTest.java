package io.orgkeep.components.catalog;

import io.orgkeep.api.model.Catalogs;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.UpdateCatalogRequest;
import io.orgkeep.fixtures.TestRealm;
import io.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogs managed by the realm owner: security, validation, status codes, and the effects in Keycloak
 * (client, client scope, default scopes). Each test works in a throwaway realm (see OrganisationsResourceTest).
 */
@QuarkusTest
class CatalogsResourceTest {
    private static final String BASE = "/api/admin/catalogs";
    private static final String SANDBOX = "products-sandbox";

    @Inject Keycloak keycloak;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403() {
        given().when().get(BASE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createReturns201ThenListAndGet() {
        createCatalog(SANDBOX);

        given().when().get(BASE)
                .then().statusCode(200)
                .body("name", contains(SANDBOX))
                .body("[0].displayName", equalTo("Sandbox"))
                .body("[0].productCount", equalTo(0));
        given().when().get(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(200)
                .body("description", equalTo("Test environment"));
    }

    // optional, not default: a token only carries the catalog when its client has it by default or the app asks for it.
    // Every client can ask for it, the existing login client included
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createAddsTheScopeToTheRealmOptionalScopesAndToTheLoginClient() {
        String loginClientUuid = testRealm.createClient(testRealm.name());

        createCatalog(SANDBOX);

        assertTrue(scopeNames(testRealm.resource().getDefaultOptionalClientScopes()).contains(SANDBOX));
        assertFalse(scopeNames(testRealm.resource().getDefaultDefaultClientScopes()).contains(SANDBOX));
        assertTrue(scopeNames(testRealm.resource().clients().get(loginClientUuid).getOptionalClientScopes())
                .contains(SANDBOX));
        assertFalse(scopeNames(testRealm.resource().clients().get(loginClientUuid).getDefaultClientScopes())
                .contains(SANDBOX));
        assertFalse(testRealm.resource().clients().findByClientId(SANDBOX).get(0).isStandardFlowEnabled());
        ClientScopeRepresentation scope = testRealm.resource().clientScopes().findAll().stream()
                .filter(s -> SANDBOX.equals(s.getName()))
                .findFirst().orElseThrow();
        assertEquals(Catalogs.MARKER_VALUE, scope.getAttributes().get(Catalogs.MARKER_ATTRIBUTE));
    }

    // only the catalogs are listed, not the other clients of the realm
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void listIgnoresTheOtherClients() {
        testRealm.createClient("not-a-catalog");

        given().when().get(BASE)
                .then().statusCode(200)
                .body("$", empty());
        given().when().get(BASE + "/{catalog}", "not-a-catalog")
                .then().statusCode(404);
    }

    // "account" is a client of every realm, "basic" a client scope of every realm
    @ParameterizedTest
    @ValueSource(strings = {"account", "basic"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithANameTakenByAClientOrAScopeReturns409(String name) {
        given().contentType(ContentType.JSON).body(new CreateCatalogRequest(name, "Taken", null))
                .when().post(BASE)
                .then().statusCode(409);
    }

    // a slug, neither a claim of the token nor the prefix of the org clients
    @ParameterizedTest
    @ValueSource(strings = {"Products", "products_eu", "sub", "scope", "org-acme", ""})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithAnInvalidNameReturns400(String name) {
        given().contentType(ContentType.JSON).body(new CreateCatalogRequest(name, "Invalid", null))
                .when().post(BASE)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void deleteRemovesTheClientAndTheScope() {
        String loginClientUuid = testRealm.createClient(testRealm.name());
        createCatalog(SANDBOX);

        given().when().delete(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(204);

        assertTrue(testRealm.resource().clients().findByClientId(SANDBOX).isEmpty());
        assertFalse(scopeNames(testRealm.resource().clientScopes().findAll()).contains(SANDBOX));
        // Keycloak deletes a scope in use, and removes it from the clients having it
        assertFalse(scopeNames(testRealm.resource().clients().get(loginClientUuid).getOptionalClientScopes())
                .contains(SANDBOX));
        assertFalse(scopeNames(testRealm.resource().getDefaultOptionalClientScopes()).contains(SANDBOX));
        given().when().delete(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void updateChangesDisplayNameAndDescription() {
        createCatalog(SANDBOX);

        given().contentType(ContentType.JSON).body(new UpdateCatalogRequest("Sandbox EU", null))
                .when().put(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(200)
                .body("name", equalTo(SANDBOX))
                .body("displayName", equalTo("Sandbox EU"))
                .body("description", nullValue());
        assertEquals("Sandbox EU", testRealm.resource().clients().findByClientId(SANDBOX).get(0).getName());

        given().contentType(ContentType.JSON).body(new UpdateCatalogRequest("", null))
                .when().put(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(new UpdateCatalogRequest("Unknown", null))
                .when().put(BASE + "/{catalog}", "unknown")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void deleteWithProductsReturns409() {
        createCatalog(SANDBOX);
        given().contentType(ContentType.JSON)
                .body(new CreateProductRequest("billing", "Billing", null, List.of(new Feature("read", null)), List.of()))
                .when().post(BASE + "/{catalog}/products", SANDBOX)
                .then().statusCode(201);

        // the feature of the product is not a product
        given().when().get(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(200)
                .body("productCount", equalTo(1));
        given().when().delete(BASE + "/{catalog}", SANDBOX)
                .then().statusCode(409);
    }

    private void createCatalog(String name) {
        given().contentType(ContentType.JSON).body(new CreateCatalogRequest(name, "Sandbox", "Test environment"))
                .when().post(BASE)
                .then().statusCode(201)
                .body("name", equalTo(name))
                .body("productCount", equalTo(0));
    }

    private static Set<String> scopeNames(List<ClientScopeRepresentation> scopes) {
        return scopes.stream()
                .map(ClientScopeRepresentation::getName)
                .collect(Collectors.toSet());
    }
}
