package org.orgkeep.components.organisation.serviceaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CatalogCredentials;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.fixtures.TestRealm;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real client credentials grants, both ways of sending the credentials (HTTP Basic and form post), and their
 * introspection by the client of a catalog, like the APIs of the realm owner do.
 * <p>
 * Part 1 only: a service account has no team yet, so it gets a catalog role granted directly on its user, which is
 * what puts the catalog in the audience of its token (a client only introspects the tokens it is the audience of).
 */
@Slf4j
@QuarkusTest
class ServiceAccountTokenTest {
    private static final String ORG = "acme";
    private static final String CLIENT_ID = "org-acme.billing";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject ProductService productService;
    @Inject ServiceAccountService serviceAccountService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        realmProvisioner.ensureDefaults(realm());
        catalogService.create(realm(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(realm(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(realm(), "prod", new CreateProductRequest("billing", "Billing", null));
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        productService.assign(realm(), ORG, "prod", "billing", new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
        catalogRoleService.create(realm(), "prod", new CreateCatalogRoleRequest("searcher", null, null, true));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void basicAndPostGiveTheSameIdentityAndTheOrganizationClaim() throws Exception {
        ServiceAccountCreated created = create(List.of());
        String secret = created.credentials().clientSecret();

        for (boolean basic : new boolean[]{true, false}) {
            Grant grant = grant(secret, basic, null);
            assertEquals(200, grant.status(), grant.body());
            JsonNode claims = claims(grant);
            log.info("Token of the service account ({}): {}", basic ? "basic" : "post", claims.toPrettyString());

            assertEquals(CLIENT_ID, claims.path("azp").asText());
            assertEquals(created.serviceAccount().userId(), claims.path("sub").asText());
            assertEquals(CLIENT_ID, claims.path("client_id").asText());
            // the shape of the native claim of a user: an array of aliases
            assertEquals("[\"acme\"]", claims.path("organization").toString());
            // no org role: the core answers 403 to such a token
            assertTrue(claims.at("/resource_access/org-acme").isMissingNode(), claims.toString());
            assertTrue(claims.path("email").isMissingNode(), claims.toString());
        }
    }

    // the catalog client introspects it: the claim is there too, from the mapper (introspection.token.claim)
    @Test
    void introspectionByTheCatalogClientHasTheOrganizationClaim() throws Exception {
        ServiceAccountCreated created = create(List.of());
        grantSearcher(created);

        Grant grant = grant(created.credentials().clientSecret(), true, "prod");
        JsonNode introspection = introspect(grant.accessToken());
        log.info("Introspection by prod: {}", introspection.toPrettyString());

        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        assertEquals("[\"acme\"]", introspection.path("organization").toString());
        assertEquals(CLIENT_ID, introspection.path("azp").asText());
        assertEquals(Set.of("searcher"), strings(introspection.at("/resource_access/prod/roles")));
    }

    // a catalog is in the token when the service account has it by default, or when the application asks for it
    @Test
    void catalogIsInTheTokenByDefaultOrAskedWithTheScope() throws Exception {
        ServiceAccountCreated none = create("none", List.of());
        ServiceAccountCreated byDefault = create("default", List.of("prod"));
        grantSearcher(none);
        grantSearcher(byDefault);

        JsonNode notAsked = claims(grant(none.credentials().clientSecret(), "none", true, null));
        assertTrue(notAsked.at("/resource_access/prod").isMissingNode(), notAsked.toString());

        JsonNode asked = claims(grant(none.credentials().clientSecret(), "none", true, "prod"));
        assertEquals(Set.of("searcher"), strings(asked.at("/resource_access/prod/roles")));

        JsonNode defaulted = claims(grant(byDefault.credentials().clientSecret(), "default", true, null));
        assertEquals(Set.of("searcher"), strings(defaulted.at("/resource_access/prod/roles")));
    }

    // a catalog created after the service account: at the first product of the org in it, it becomes an optional scope
    // of the client, so that the application can ask for it
    @Test
    void catalogCreatedAfterTheServiceAccountCanBeAskedOnceTheOrgHasAProduct() throws Exception {
        ServiceAccountCreated created = create(List.of());
        catalogService.create(realm(), new CreateCatalogRequest("late", "Late", null));
        productService.create(realm(), "late", new CreateProductRequest("batch", "Batch", null));
        catalogRoleService.create(realm(), "late", new CreateCatalogRoleRequest("runner", null, null, true));
        String lateUuid = testRealm.resource().clients().findByClientId("late").getFirst().getId();
        RoleRepresentation role = testRealm.resource().clients().get(lateUuid).roles().get("runner").toRepresentation();
        testRealm.resource().users().get(created.serviceAccount().userId()).roles().clientLevel(lateUuid).add(List.of(role));

        // the org has nothing in it yet: the scope is not on the client
        assertEquals(400, grant(created.credentials().clientSecret(), true, "late").status());

        productService.assign(realm(), ORG, "late", "batch", new AssignProductRequest(10L, QuotaCycle.DAILY));

        JsonNode claims = claims(grant(created.credentials().clientSecret(), true, "late"));
        assertEquals(Set.of("runner"), strings(claims.at("/resource_access/late/roles")));
    }

    @Test
    void rotationInvalidatesTheOldSecretAtOnce() throws Exception {
        String oldSecret = create(List.of()).credentials().clientSecret();
        assertEquals(200, grant(oldSecret, true, null).status());

        String newSecret = serviceAccountService.regenerateSecret(realm(), ORG, "billing").clientSecret();

        assertEquals(401, grant(oldSecret, true, null).status());
        assertEquals(401, grant(oldSecret, false, null).status());
        assertEquals(200, grant(newSecret, true, null).status());
        assertEquals(200, grant(newSecret, false, null).status());
    }

    // Disabling stops the new tokens, and closes the sessions: Keycloak sets the notBefore of the user to the current
    // second, so the introspection rejects every token issued before, for good (also after enabling again). A JWT is
    // still valid by its signature until its exp, for an API that does not introspect. The notBefore has the precision
    // of a second: the test waits for the next one between the grant and the disable, or a token issued in the same
    // second as the disable would stay active
    @Test
    void disabledServiceAccountGetsNoTokenAndItsEarlierTokenIsInactive() throws Exception {
        ServiceAccountCreated created = create(List.of());
        grantSearcher(created);
        String secret = created.credentials().clientSecret();
        String earlier = grant(secret, true, "prod").accessToken();
        assertTrue(introspect(earlier).path("active").asBoolean());
        Thread.sleep(1100);

        assertFalse(serviceAccountService.setEnabled(realm(), ORG, "billing", false).enabled());

        assertEquals(401, grant(secret, true, null).status());
        assertEquals(401, grant(secret, false, null).status());
        assertFalse(introspect(earlier).path("active").asBoolean());

        assertTrue(serviceAccountService.setEnabled(realm(), ORG, "billing", true).enabled());

        // new tokens again, but the earlier one stays revoked for the APIs that introspect
        Grant later = grant(secret, true, "prod");
        assertEquals(200, later.status());
        assertTrue(introspect(later.accessToken()).path("active").asBoolean());
        assertFalse(introspect(earlier).path("active").asBoolean(), "disabling sets the notBefore of the user");
    }

    @Test
    void deletedServiceAccountGetsNoToken() throws Exception {
        String secret = create(List.of()).credentials().clientSecret();

        serviceAccountService.delete(realm(), ORG, "billing");

        assertEquals(401, grant(secret, true, null).status());
    }

    // --- helpers

    private ServiceAccountCreated create(List<String> defaultCatalogs) {
        return create("billing", defaultCatalogs);
    }

    private ServiceAccountCreated create(String name, List<String> defaultCatalogs) {
        return serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest(name, null, defaultCatalogs));
    }

    /** The role "searcher" of prod granted directly to the user of the service account (a reserved role, part 2). */
    private void grantSearcher(ServiceAccountCreated created) {
        String prodUuid = testRealm.resource().clients().findByClientId("prod").getFirst().getId();
        RoleRepresentation role = testRealm.resource().clients().get(prodUuid).roles().get("searcher").toRepresentation();
        testRealm.resource().users().get(created.serviceAccount().userId()).roles().clientLevel(prodUuid).add(List.of(role));
    }

    private record Grant(int status, String body) {
        String accessToken() throws Exception {
            return new ObjectMapper().readTree(body).path("access_token").asText();
        }
    }

    private Grant grant(String secret, boolean basic, String scope) throws Exception {
        return grant(secret, "billing", basic, scope);
    }

    private Grant grant(String secret, String name, boolean basic, String scope) throws Exception {
        String clientId = "org-acme." + name;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scope != null) {
            form.put("scope", scope);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (basic) {
            request.header("Authorization", basicAuth(clientId, secret));
        } else {
            form.put("client_id", clientId);
            form.put("client_secret", secret);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(encode(form))).build());
    }

    /** The introspection by the client of the catalog prod (RFC 7662), like the APIs of the realm owner. */
    private JsonNode introspect(String token) throws Exception {
        CatalogCredentials prod = catalogService.regenerateSecret(realm(), "prod");
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl() + "/introspect"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", basicAuth(prod.clientId(), prod.clientSecret()))
                .POST(HttpRequest.BodyPublishers.ofString(encode(Map.of("token", token))))
                .build();
        Grant answer = send(request);
        assertEquals(200, answer.status(), answer.body());
        return json.readTree(answer.body());
    }

    private JsonNode claims(Grant grant) throws Exception {
        assertEquals(200, grant.status(), grant.body());
        return json.readTree(Base64.getUrlDecoder().decode(grant.accessToken().split("\\.")[1]));
    }

    private static Grant send(HttpRequest request) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Grant(response.statusCode(), response.body());
        }
    }

    private String tokenUrl() {
        return keycloakUrl + "/realms/" + realm() + "/protocol/openid-connect/token";
    }

    private static String basicAuth(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new java.util.HashSet<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private String realm() {
        return testRealm.name();
    }
}
