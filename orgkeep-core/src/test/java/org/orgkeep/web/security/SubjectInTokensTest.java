package org.orgkeep.web.security;

import org.orgkeep.api.LoginClientService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.fixtures.TestRealm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The core reads the "sub" of the access token to know who calls (a member cannot disable themselves). Keycloak
 * dropped the sub mapper from the clients once (it is now in the "basic" client scope, a default scope of every new
 * client), so this checks it on real tokens of every kind of client the core serves: the login client,
 * orgkeep-api, and a client created like the one of the dashboard (lightweight tokens: see below).
 */
@QuarkusTest
class SubjectInTokensTest {
    private static final String PASSWORD = "password";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject LoginClientService loginClientService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String paulId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        realmProvisioner.ensureDefaults(testRealm.name());
        orgService.create(testRealm.name(), new CreateOrgRequest("acme", Set.of(), null, null));

        UserRepresentation user = new UserRepresentation();
        user.setUsername("paul");
        user.setEmail("paul@acme.test");
        user.setEmailVerified(true);
        user.setFirstName("Paul");
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        testRealm.createUser(user);
        paulId = memberService.add(testRealm.name(), "acme", new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void loginClientTokenHasTheSubject() throws Exception {
        allowPasswordGrant(testRealm.name());

        assertEquals(paulId, subjectOf(testRealm.name(), null));
    }

    // Measured: a lightweight access token of Keycloak has no sub (nor aud): the APIs read it back by introspection.
    // The core never serves such a token (LightweightTokenFilter answers 401, LightweightTokenFilterTest), so the
    // sub of the callers of the core does not depend on a mapper on the login client: adding one would change the
    // lightweight format for nothing
    @Test
    void lightweightLoginClientTokenIsMarkedAndRefusedByTheCoreInsteadOfLackingASubjectSilently() throws Exception {
        allowPasswordGrant(testRealm.name());
        loginClientService.update(testRealm.name(),
                new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, true));

        JsonNode claims = claimsOf(testRealm.name(), null);

        assertEquals(true, claims.path("lightweight").asBoolean(), claims.toString());
    }

    @Test
    void orgkeepApiTokenHasTheSubject() throws Exception {
        allowPasswordGrant("orgkeep-api");

        assertEquals(paulId, subjectOf("orgkeep-api", null));
    }

    // created as DemoSeeder creates the dashboard client: nothing but the defaults of Keycloak
    @Test
    void aClientCreatedWithTheDefaultsHasTheSubject() throws Exception {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("orgkeep-dashboard");
        client.setPublicClient(false);
        client.setSecret("secret");
        client.setStandardFlowEnabled(true);
        client.setDirectAccessGrantsEnabled(true);
        testRealm.resource().clients().create(client).close();

        assertEquals(paulId, subjectOf("orgkeep-dashboard", "secret"));
    }

    private void allowPasswordGrant(String clientId) {
        var clients = testRealm.resource().clients();
        String uuid = clients.findByClientId(clientId).get(0).getId();
        ClientRepresentation client = clients.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clients.get(uuid).update(client);
    }

    /** The "sub" of the access token of Paul for this client. */
    private String subjectOf(String clientId, String secret) throws Exception {
        JsonNode claims = claimsOf(clientId, secret);
        assertFalse(claims.path("sub").isMissingNode(), "no sub in " + claims);
        return claims.path("sub").asText();
    }

    private JsonNode claimsOf(String clientId, String secret) throws Exception {
        Map<String, String> form = new java.util.HashMap<>(Map.of("grant_type", "password", "client_id", clientId,
                "username", "paul", "password", PASSWORD));
        if (secret != null) {
            form.put("client_secret", secret);
        }
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            String accessToken = json.readTree(response.body()).path("access_token").asText();
            return json.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        }
    }
}
