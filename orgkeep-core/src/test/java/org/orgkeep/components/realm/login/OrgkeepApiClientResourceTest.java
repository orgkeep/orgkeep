package org.orgkeep.components.realm.login;

import org.orgkeep.api.LoginClientService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.UpdateOrgkeepApiClientRequest;
import org.orgkeep.fixtures.RequestScope;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The URIs of the client orgkeep-api, set by the realm owner: status codes, the URI validation (the same rules as the
 * login client, see LoginClientResourceTest) and the effects on the Keycloak client.
 * <p>
 * One realm for the whole class: the URIs of the client are reset after each test (@AfterEach) so a test that
 * sets them does not leak into the next one.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgkeepApiClientResourceTest {
    private static final String BASE = "/api/admin/orgkeep-api-client";

    @Inject RealmProvisioner realmProvisioner;
    @Inject LoginClientService loginClientService;
    @InjectMock CurrentRealm currentRealm;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        // creates the login client and orgkeep-api, as the bootstrap does for the realm of the app
        RequestScope.run(() -> realmProvisioner.ensureDefaults(testRealm.name()));
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void resetUris() {
        loginClientService.updateOrgkeepApiClient(testRealm.name(),
                new UpdateOrgkeepApiClientRequest(List.of(), List.of(), List.of()));
    }

    @AfterAll
    void deleteRealm() {
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
    void getReturnsTheClientWithoutUri() {
        given().when().get(BASE)
                .then().statusCode(200)
                .body("clientId", equalTo(OrgkeepApiClient.CLIENT_ID))
                .body("issuer", equalTo(keycloakUrl + "/realms/" + testRealm.name()))
                .body("redirectUris", empty())
                .body("postLogoutRedirectUris", empty())
                .body("webOrigins", empty());
    }

    // the URIs change, the rest of the client stays as it was (PKCE, only the roles of its scope)
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void updateSetsUrisAndKeepsOtherSettings() {
        UpdateOrgkeepApiClientRequest request = new UpdateOrgkeepApiClientRequest(
                List.of("https://admin.example.com/*"), List.of("https://admin.example.com/"), List.of("+"));

        put(request)
                .then().statusCode(200)
                .body("redirectUris", containsInAnyOrder(request.redirectUris().toArray()))
                .body("postLogoutRedirectUris", equalTo(request.postLogoutRedirectUris()))
                .body("webOrigins", equalTo(request.webOrigins()));

        ClientRepresentation client = client(OrgkeepApiClient.CLIENT_ID);
        assertEquals(Set.copyOf(request.redirectUris()), Set.copyOf(client.getRedirectUris()));
        assertEquals("https://admin.example.com/", client.getAttributes().get("post.logout.redirect.uris"));
        assertEquals("S256", client.getAttributes().get("pkce.code.challenge.method"));
        assertFalse(client.isFullScopeAllowed());
        // the login client is another client: untouched
        assertEquals(List.of(), client(testRealm.name()).getRedirectUris());
    }

    // the same unsafe values as for the login client
    @ParameterizedTest
    @ValueSource(strings = {"*", "http://app.example.com/callback", "https://app.example.com*"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void unsafeRedirectUriReturns400(String uri) {
        put(new UpdateOrgkeepApiClientRequest(List.of(uri), List.of(), List.of()))
                .then().statusCode(400);
    }

    private Response put(UpdateOrgkeepApiClientRequest request) {
        return given().contentType(ContentType.JSON).body(request).when().put(BASE);
    }

    private ClientRepresentation client(String clientId) {
        return testRealm.resource().clients().findByClientId(clientId).get(0);
    }
}
