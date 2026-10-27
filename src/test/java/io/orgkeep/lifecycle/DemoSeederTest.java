package io.orgkeep.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.fixtures.CatalogClaims;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the application with the demo settings and checks that the demo accounts can log in right away.
 * <p>
 * A QuarkusTestProfile overrides configuration for one test class. Quarkus restarts the application
 * for it (and again for the next class without profile): keep such classes few.
 */
@QuarkusTest
@TestProfile(DemoSeederTest.DemoProfile.class)
class DemoSeederTest {
    private static final String REALM = "demo-it";

    public static class DemoProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Map.of takes at most 10 pairs
            return Map.ofEntries(
                    Map.entry("orgkeep.realm-name", REALM),
                    Map.entry("quarkus.keycloak.devservices.realm-name", REALM),
                    Map.entry("orgkeep.owner.email", "realm-owner@demo.test"),
                    Map.entry("orgkeep.owner.username", "realm-owner"),
                    Map.entry("orgkeep.owner.password", DemoSeeder.PASSWORD),
                    Map.entry("orgkeep.owner.temporary-password", "false"),
                    Map.entry("orgkeep.owner.first-name", "Alice"),
                    Map.entry("orgkeep.owner.last-name", "Martin"),
                    Map.entry("orgkeep.demo.enabled", "true"),
                    // the theme is not deployed in the Keycloak of the tests: only the attribute of the client is checked
                    Map.entry("orgkeep.dashboard.login-theme", "orgkeep"));
        }
    }

    @Inject Keycloak keycloak;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    // the roles are checked, not only the login: an admin-cli token logged in fine but had no roles, hence a 403
    @Test
    void realmOwnerTokenCarriesTheRealmOwnerRole() throws Exception {
        JsonNode claims = logIn("realm-owner");
        assertTrue(contains(claims.path("realm_access").path("roles"), "realm-owner"), claims.toString());
    }

    @Test
    void orgOwnerTokenCarriesTheOrgOwnerRole() throws Exception {
        JsonNode claims = logIn(DemoSeeder.ORG_OWNER);
        JsonNode orgRoles = claims.path("resource_access").path("org-" + DemoSeeder.ORG).path("roles");
        assertTrue(contains(orgRoles, "org-owner"), claims.toString());
    }

    @Test
    void orgOwnerOwnsTheDemoOrg() {
        RealmResource realm = keycloak.realm(REALM);
        String userId = realm.users().searchByUsername(DemoSeeder.ORG_OWNER, true).get(0).getId();
        String clientUuid = realm.clients().findByClientId("org-" + DemoSeeder.ORG).get(0).getId();

        Set<String> roles = realm.users().get(userId).roles().clientLevel(clientUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("org-owner"), roles);
        assertFalse(realm.organizations().search(DemoSeeder.ORG).isEmpty());
    }

    // a plain member: in the default group users, hence org-user (and nothing more) in the token
    @Test
    void orgUserIsAMemberOfTheUsersGroup() throws Exception {
        RealmResource realm = keycloak.realm(REALM);
        String userId = realm.users().searchByUsername(DemoSeeder.ORG_USER, true).get(0).getId();
        Set<String> groupPaths = realm.users().get(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .collect(Collectors.toSet());
        assertEquals(Set.of("/" + DemoSeeder.ORG + "/user-groups/users"), groupPaths);

        JsonNode orgRoles = logIn(DemoSeeder.ORG_USER).path("resource_access").path("org-" + DemoSeeder.ORG).path("roles");
        assertTrue(contains(orgRoles, "org-user"), orgRoles.toString());
        assertFalse(contains(orgRoles, "org-admin"), orgRoles.toString());
    }

    // the simple case: billing given by the team users, in the default catalog products (a default scope of the
    // login client): every member has it without asking for a scope
    @Test
    void orgUserGetsBillingThroughTheTeamUsers() throws Exception {
        JsonNode claims = logIn(DemoSeeder.ORG_USER);

        assertTrue(contains(claims.at("/resource_access/products/roles"), DemoSeeder.BILLING), claims.toString());
        assertEquals("/", claims.at("/products.billing/quotas/0/pool").asText(), claims.toString());
    }

    // the example token of the spec: Paul, team sales, scope prod. Metered products: no access role, the features
    // given as roles, a claim per metered feature with the pool of the org, plus the pool of sales for scan on billing
    @Test
    void paulGetsTheExampleTokenOfTheSpec() throws Exception {
        JsonNode claims = logIn(DemoSeeder.PAUL, "openid prod");

        assertEquals(Set.of("billing:stats", "billing:scan", "shop:write", "shop:scan"),
                strings(claims.at("/resource_access/prod/roles")));
        // two values of a multivalued attribute: compared as a set, Keycloak does not promise their order
        assertEquals(Set.of(json("{\"pool\":\"/\",\"limit\":200,\"cycle\":\"daily\"}"),
                        json("{\"pool\":\"/sales\",\"limit\":50,\"cycle\":\"daily\"}")),
                elements(claims.at("/prod.billing.scan/quotas")));
        assertEquals(json("[{\"pool\":\"/\",\"limit\":100,\"cycle\":\"daily\"}]"), claims.at("/prod.shop.scan/quotas"));
        // without the scope of sandbox, nothing of it
        assertTrue(claims.at("/resource_access/sandbox").isMissingNode(), claims.toString());
    }

    // Emma, team accounting: every feature of billing, a pool per engine; the sandbox has its own, larger quotas
    @Test
    void emmaGetsEveryFeatureOfBilling() throws Exception {
        JsonNode claims = logIn(DemoSeeder.EMMA, "openid prod sandbox");

        assertEquals(Set.of("billing:read", "billing:write", "billing:stats", "billing:scan", "billing:flow"),
                strings(claims.at("/resource_access/prod/roles")));
        assertEquals(Set.of("scan:200", "flow:100"), limits(claims, "prod.billing"));
        assertEquals(Set.of("scan:1000", "flow:1000"), limits(claims, "sandbox.billing"));
        assertTrue(CatalogClaims.of(claims, "prod.shop").isEmpty(), claims.toString());
    }

    @Test
    void dashboardClientIsReadyForTheDashboardInDev() {
        ClientRepresentation client = keycloak.realm(REALM).clients()
                .findByClientId(DemoSeeder.DASHBOARD_CLIENT_ID).get(0);

        assertFalse(client.isPublicClient());
        assertTrue(client.isStandardFlowEnabled());
        assertEquals(List.of("http://localhost:8284/*"), client.getRedirectUris());
        assertEquals(DemoSeeder.DASHBOARD_SECRET,
                keycloak.realm(REALM).clients().get(client.getId()).getSecret().getValue());
        // set by the seeder: OnPremiseBootstrap ran before the client existed
        assertEquals("orgkeep", client.getAttributes().get("login_theme"));
        // white label: the login client of the users of the orgs keeps the pages of Keycloak
        ClientRepresentation loginClient = keycloak.realm(REALM).clients().findByClientId(REALM).get(0);
        assertNull(loginClient.getAttributes().get("login_theme"));
    }

    /**
     * Password grant through the login client of the realm (clientId = realm name), as demo.http does.
     * @return the claims of the access token
     */
    private JsonNode logIn(String username) throws Exception {
        try (Keycloak client = Keycloak.getInstance(keycloakUrl, REALM, username, DemoSeeder.PASSWORD, REALM)) {
            String token = client.tokenManager().getAccessTokenString();
            // header.payload.signature: the payload is base64url encoded JSON
            byte[] payload = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
            return new ObjectMapper().readTree(payload);
        }
    }

    /**
     * Same, with the scope parameter: the catalogs prod and sandbox are optional scopes of the login client.
     * A plain HTTP call, the admin client does not ask for a scope.
     */
    private JsonNode logIn(String username, String scope) throws Exception {
        String form = Map.of(
                        "grant_type", "password",
                        "client_id", REALM,
                        "username", username,
                        "password", DemoSeeder.PASSWORD,
                        "scope", scope)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloakUrl + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(200, response.statusCode(), response.body());
        String token = new ObjectMapper().readTree(response.body()).path("access_token").asText();
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    /** The elements of a JSON array, for a comparison that ignores their order. */
    private static Set<JsonNode> elements(JsonNode array) {
        Set<JsonNode> elements = new HashSet<>();
        array.forEach(elements::add);
        return elements;
    }

    /** "feature:limit" for each pool of the metered features of a product, read from their claims ("prod.billing.scan"). */
    private static Set<String> limits(JsonNode claims, String product) {
        Set<String> limits = new HashSet<>();
        CatalogClaims.of(claims, product).properties().forEach(claim -> claim.getValue().path("quotas")
                .forEach(pool -> limits.add(claim.getKey().substring(product.length() + 1) + ":" + pool.path("limit").asInt())));
        return limits;
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) {
            if (value.equals(element.asText())) {
                return true;
            }
        }
        return false;
    }
}
