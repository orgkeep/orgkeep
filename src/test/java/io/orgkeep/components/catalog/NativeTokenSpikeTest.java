package io.orgkeep.components.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike: can the token of the catalog model be built with native Keycloak mappers only, without an extension?
 * See "Catalogues, produits et teams" in ARCHITECTURE.md.
 * <p>
 * Built by hand through the admin API, as the core will do it: a catalog "prod" (client + optional client scope),
 * a product and a product role as real client roles granted to two teams, the quota pools as multivalued JSON
 * attributes of the teams, read by one "User Attribute" mapper with aggregate.attrs. Then real tokens, through a
 * password grant on a login client with fullScopeAllowed=false, with and without scope=prod.
 */
@Slf4j
@QuarkusTest
class NativeTokenSpikeTest {
    private static final String CATALOG = "prod";
    private static final String PRODUCT = "shop-repository";
    private static final String PRODUCT_ROLE = PRODUCT + ":scan";
    private static final String CATALOG_ROLE = "scanner";
    private static final String QUOTAS = CATALOG + "." + PRODUCT + ".quotas";
    private static final String LOGIN_CLIENT = "app";
    private static final String PASSWORD = "password";
    private static final String ORG_POOL = "{\"pool\":\"acme\",\"limit\":1000,\"cycle\":\"monthly\"}";
    private static final String TEAM_POOL = "{\"pool\":\"acme/t1\",\"limit\":300,\"cycle\":\"monthly\"}";

    @Inject Keycloak keycloak;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;
    private String loginClientUuid;
    private String userId;

    @BeforeEach
    void setUp() {
        testRealm = TestRealm.create(keycloak);
        String catalogUuid = testRealm.createClient(CATALOG);
        for (String role : List.of(PRODUCT, PRODUCT_ROLE, CATALOG_ROLE)) {
            realm().clients().get(catalogUuid).roles().create(new RoleRepresentation(role, null, false));
        }
        List<RoleRepresentation> catalogRoles = realm().clients().get(catalogUuid).roles().list(false);

        // the catalog scope: maps every role of the catalog client, and carries the quota mapper
        String scopeId = createScope(CATALOG);
        realm().clientScopes().get(scopeId).getScopeMappings().clientLevel(catalogUuid).add(catalogRoles);
        realm().clientScopes().get(scopeId).getProtocolMappers().createMapper(quotaMapper());

        loginClientUuid = createLoginClient();
        realm().clients().get(loginClientUuid).addOptionalClientScope(scopeId);

        // two teams giving the product: both carry the org pool, t1 its own pool too
        String t1 = createGroup("t1", Map.of(QUOTAS, List.of(ORG_POOL, TEAM_POOL)));
        String t2 = createGroup("t2", Map.of(QUOTAS, List.of(ORG_POOL)));
        grant(t1, catalogUuid, PRODUCT, PRODUCT_ROLE, CATALOG_ROLE);
        grant(t2, catalogUuid, PRODUCT);

        userId = createUser("paul");
        realm().users().get(userId).joinGroup(t1);
        realm().users().get(userId).joinGroup(t2);
    }

    @AfterEach
    void tearDown() {
        testRealm.close();
    }

    @Test
    void withTheCatalogScope() throws Exception {
        JsonNode claims = logIn("openid " + CATALOG);
        log.info("Token with scope={}: {}", CATALOG, claims.toPrettyString());

        // ":" in a role name, roles of the groups, filtered by the scope mappings
        assertEquals(Set.of(PRODUCT, PRODUCT_ROLE, CATALOG_ROLE),
                strings(claims.path("resource_access").path(CATALOG).path("roles")));
        // audience resolve: the catalog is in aud as soon as the user has one of its roles in scope
        assertTrue(strings(claims.path("aud")).contains(CATALOG), claims.path("aud").toString());

        // nested claim, JSON objects in an array, org pool given by both teams only once
        JsonNode quotas = claims.path(CATALOG).path(PRODUCT).path("quotas");
        assertTrue(quotas.isArray(), quotas.toString());
        assertEquals(2, quotas.size(), quotas.toString());
        assertEquals(Set.of("acme", "acme/t1"), pools(quotas));
        assertEquals(1000, quotas.findValues("limit").stream().mapToInt(JsonNode::asInt).max().orElseThrow());
    }

    @Test
    void withoutTheCatalogScope() throws Exception {
        JsonNode claims = logIn("openid");
        log.info("Token without scope={}: {}", CATALOG, claims.toPrettyString());

        assertTrue(claims.path("resource_access").path(CATALOG).isMissingNode(), claims.toString());
        assertTrue(claims.path(CATALOG).isMissingNode(), claims.toString());
        assertFalse(strings(claims.path("aud")).contains(CATALOG), claims.path("aud").toString());
    }

    // a single team pool: still an array of one object, the API reads the same shape
    @Test
    void singlePoolIsStillAnArray() throws Exception {
        realm().users().get(userId).leaveGroup(groupId("t1"));

        JsonNode quotas = logIn("openid " + CATALOG).path(CATALOG).path(PRODUCT).path("quotas");

        assertTrue(quotas.isArray(), quotas.toString());
        assertEquals(Set.of("acme"), pools(quotas));
    }

    // observation only: does Keycloak still log in (and give the rights to) a member of a disabled organization?
    @Test
    void disabledOrganization() throws Exception {
        OrganizationRepresentation org = new OrganizationRepresentation();
        org.setName("acme");
        org.setAlias("acme");
        org.setEnabled(true);
        org.addDomain(new OrganizationDomainRepresentation("acme.test"));
        String orgId;
        try (Response response = realm().organizations().create(org)) {
            orgId = CreatedResponseUtil.getCreatedId(response);
        }
        try (Response response = realm().organizations().get(orgId).members().addMember(userId)) {
            assertEquals(201, response.getStatus());
        }
        OrganizationRepresentation disabled = realm().organizations().get(orgId).toRepresentation();
        disabled.setEnabled(false);
        try (Response response = realm().organizations().get(orgId).update(disabled)) {
            assertEquals(204, response.getStatus());
        }

        HttpResponse<String> response = tokenRequest("openid " + CATALOG);
        log.info("Disabled organization: HTTP {} {}", response.statusCode(), response.body());
    }

    // observation only: what a client in lightweight access token mode receives
    @Test
    void lightweightAccessToken() throws Exception {
        ClientRepresentation client = realm().clients().get(loginClientUuid).toRepresentation();
        client.getAttributes().put("client.use.lightweight.access.token.enabled", "true");
        realm().clients().get(loginClientUuid).update(client);

        JsonNode claims = logIn("openid " + CATALOG);
        log.info("Lightweight token: {}", claims.toPrettyString());
        assertFalse(claims.path(CATALOG).isMissingNode(), "the quota mapper has lightweight.claim=true");
    }

    private ProtocolMapperRepresentation quotaMapper() {
        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", QUOTAS);
        config.put("claim.name", QUOTAS);
        config.put("jsonType.label", "JSON");
        config.put("multivalued", "true");
        config.put("aggregate.attrs", "true");
        config.put("access.token.claim", "true");
        config.put("introspection.token.claim", "true");
        config.put("lightweight.claim", "true");
        config.put("id.token.claim", "false");
        config.put("userinfo.token.claim", "false");

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(QUOTAS);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");
        mapper.setConfig(config);
        return mapper;
    }

    private String createScope(String name) {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(name);
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of("include.in.token.scope", "true"));
        try (Response response = realm().clientScopes().create(scope)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    /** Public client, password grant, and only the roles of its scopes in its tokens. */
    private String createLoginClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(LOGIN_CLIENT);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setFullScopeAllowed(false);
        client.setAttributes(new HashMap<>());
        try (Response response = realm().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String createGroup(String name, Map<String, List<String>> attributes) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        group.setAttributes(attributes);
        try (Response response = realm().groups().add(group)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String groupId(String name) {
        return realm().getGroupByPath("/" + name).getId();
    }

    private void grant(String groupId, String clientUuid, String... roles) {
        List<RoleRepresentation> representations = new ArrayList<>();
        for (String role : roles) {
            representations.add(realm().clients().get(clientUuid).roles().get(role).toRepresentation());
        }
        realm().groups().group(groupId).roles().clientLevel(clientUuid).add(representations);
    }

    /** A user who can log in by password grant right away (complete profile, no required action). */
    private String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEmailVerified(true);
        user.setFirstName("Paul");
        user.setLastName("Martin");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        return testRealm.createUser(user);
    }

    private JsonNode logIn(String scope) throws Exception {
        HttpResponse<String> response = tokenRequest(scope);
        assertEquals(200, response.statusCode(), response.body());
        String token = new ObjectMapper().readTree(response.body()).path("access_token").asText();
        // header.payload.signature: the payload is base64url encoded JSON
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private HttpResponse<String> tokenRequest(String scope) throws Exception {
        String form = Map.of(
                        "grant_type", "password",
                        "client_id", LOGIN_CLIENT,
                        "username", "paul",
                        "password", PASSWORD,
                        "scope", scope)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private RealmResource realm() {
        return testRealm.resource();
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        } else if (!node.isMissingNode()) {
            values.add(node.asText());
        }
        return values;
    }

    private static Set<String> pools(JsonNode quotas) {
        return quotas.findValues("pool").stream().map(JsonNode::asText).collect(Collectors.toSet());
    }
}
