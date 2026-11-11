package org.orgkeep.components.realm.login;

import org.orgkeep.api.CatalogService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Settings of the login client by the realm owner: status codes, the URI validation (the security of the realm
 * depends on it) and the effects on the Keycloak client.
 */
@QuarkusTest
class LoginClientResourceTest {
    private static final String BASE = "/api/admin/login-client";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @InjectMock CurrentRealm currentRealm;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        // creates the login client, as the bootstrap does for the realm of the app
        realmProvisioner.ensureDefaults(testRealm.name());
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
    void getReturnsLoginClientNamedAfterRealm() {
        given().when().get(BASE)
                .then().statusCode(200)
                .body("clientId", equalTo(testRealm.name()))
                // no orgkeep.keycloak-public-url in test: the Keycloak of Dev Services, under the realm of the call
                .body("issuer", equalTo(keycloakUrl + "/realms/" + testRealm.name()))
                .body("consoleUrl", equalTo(keycloakUrl + "/admin/" + testRealm.name() + "/console/"))
                .body("redirectUris", empty())
                .body("postLogoutRedirectUris", empty());
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void updateSetsUrisAndKeepsOtherSettings() {
        UpdateLoginClientRequest request = new UpdateLoginClientRequest(
                List.of("https://app.example.com/*", "http://localhost:5173/callback"),
                List.of("https://app.example.com/", "https://app.example.com/bye"),
                List.of("+", "https://admin.example.com"));

        given().contentType(ContentType.JSON).body(request)
                .when().put(BASE)
                .then().statusCode(200)
                // Keycloak stores redirect URIs and web origins as sets: their order is not kept
                .body("redirectUris", containsInAnyOrder(request.redirectUris().toArray()))
                .body("postLogoutRedirectUris", equalTo(request.postLogoutRedirectUris()));

        ClientRepresentation client = loginClient();
        assertEquals(Set.copyOf(request.redirectUris()), Set.copyOf(client.getRedirectUris()));
        assertEquals(Set.copyOf(request.webOrigins()), Set.copyOf(client.getWebOrigins()));
        assertEquals("https://app.example.com/##https://app.example.com/bye",
                client.getAttributes().get("post.logout.redirect.uris"));
        // the client is sent back whole: what the request does not cover must stay as it was
        assertEquals("S256", client.getAttributes().get("pkce.code.challenge.method"));
    }

    // each of these would let the authorization code leave for a host the realm owner does not control
    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            "/*",
            "http://app.example.com/callback",        // http outside of localhost
            "http://localhost.evil.com/callback",
            "https://app.example.com*",               // also matches https://app.example.com.evil.com
            "https://app.example.com/cb*",            // wildcard inside a path segment
            "https://app.example.com@evil.com/cb",    // user info hiding the real host
            "javascript:alert(1)",
            "myapp://callback"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void unsafeRedirectUriReturns400(String uri) {
        given().contentType(ContentType.JSON).body(new UpdateLoginClientRequest(List.of(uri), List.of(), List.of()))
                .when().put(BASE)
                .then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://app.example.com/path", "http://app.example.com", "https://*.example.com"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void unsafeWebOriginReturns400(String origin) {
        given().contentType(ContentType.JSON).body(new UpdateLoginClientRequest(List.of(), List.of(), List.of(origin)))
                .when().put(BASE)
                .then().statusCode(400);
    }

    // replacement semantics: every list must be given, an empty list is fine
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void missingListReturns400() {
        given().contentType(ContentType.JSON).body(Map.of("redirectUris", List.of(), "webOrigins", List.of()))
                .when().put(BASE)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void realmWithoutLoginClientReturns404() {
        testRealm.resource().clients().get(loginClient().getId()).remove();

        given().when().get(BASE)
                .then().statusCode(404);
    }

    // the default catalogs are in every token of the client, the other catalogs stay optional (scope=<catalog>)
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void defaultCatalogsAreReplaced() {
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        given().when().get(BASE)
                .then().statusCode(200)
                .body("defaultCatalogs", empty());

        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), List.of("sandbox", "prod")))
                .then().statusCode(200)
                .body("defaultCatalogs", equalTo(List.of("prod", "sandbox")));
        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), List.of("sandbox")))
                .then().statusCode(200)
                .body("defaultCatalogs", equalTo(List.of("sandbox")));

        String clientUuid = loginClient().getId();
        assertEquals(Set.of("prod"), scopeNames(testRealm.resource().clients().get(clientUuid).getOptionalClientScopes(),
                Set.of("prod", "sandbox")));
        assertEquals(Set.of("sandbox"), scopeNames(testRealm.resource().clients().get(clientUuid).getDefaultClientScopes(),
                Set.of("prod", "sandbox")));
    }

    // absent: the request only changes the URIs
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void absentDefaultCatalogsAreKept() {
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), List.of("prod"))).then().statusCode(200);

        put(new UpdateLoginClientRequest(List.of("https://app.example.com/*"), List.of(), List.of()))
                .then().statusCode(200)
                .body("defaultCatalogs", equalTo(List.of("prod")));
    }

    // "basic" is a client scope of every realm, not a catalog
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "basic"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void defaultCatalogThatIsNotACatalogReturns400(String name) {
        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), List.of(name)))
                .then().statusCode(400);
    }

    // on: the Keycloak attribute, and the scope of the marker as a default scope; off: the scope is removed,
    // a full token must not carry the marker
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void lightweightTokensSetsTheAttributeAndTheMarker() {
        given().when().get(BASE)
                .then().statusCode(200)
                .body("lightweightTokens", equalTo(false));

        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, true))
                .then().statusCode(200)
                .body("lightweightTokens", equalTo(true));
        assertEquals("true", loginClient().getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertEquals(Set.of(LightweightTokens.MARKER_CLAIM), markerScopes(true));

        // absent: unchanged
        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of()))
                .then().statusCode(200)
                .body("lightweightTokens", equalTo(true));

        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, false))
                .then().statusCode(200)
                .body("lightweightTokens", equalTo(false));
        assertEquals(Set.of(), markerScopes(true));
        assertEquals(Set.of(), markerScopes(false));
    }

    // turned off before it was ever on: nothing to remove, no scope created
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void lightweightTokensOffWithoutMarker() {
        put(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, false))
                .then().statusCode(200)
                .body("lightweightTokens", equalTo(false));
        assertEquals(List.of(), testRealm.resource().clientScopes().findAll().stream()
                .filter(scope -> scope.getName().equals(LightweightTokens.MARKER_CLAIM))
                .toList());
    }

    /** The marker scope among the default (or optional) scopes of the login client. */
    private Set<String> markerScopes(boolean defaults) {
        var client = testRealm.resource().clients().get(loginClient().getId());
        return scopeNames(defaults ? client.getDefaultClientScopes() : client.getOptionalClientScopes(),
                Set.of(LightweightTokens.MARKER_CLAIM));
    }

    private Response put(UpdateLoginClientRequest request) {
        return given().contentType(ContentType.JSON).body(request).when().put(BASE);
    }

    private static Set<String> scopeNames(List<ClientScopeRepresentation> scopes, Set<String> among) {
        return scopes.stream()
                .map(ClientScopeRepresentation::getName)
                .filter(among::contains)
                .collect(Collectors.toSet());
    }

    private ClientRepresentation loginClient() {
        return testRealm.resource().clients().findByClientId(testRealm.name()).get(0);
    }
}
