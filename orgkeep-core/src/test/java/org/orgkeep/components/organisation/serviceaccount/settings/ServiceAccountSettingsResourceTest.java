package org.orgkeep.components.organisation.serviceaccount.settings;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The four endpoints of the settings of the service accounts over REST: realm owner only, status codes, and what they
 * answer. What they write in Keycloak is in ServiceAccountSettingsServiceTest.
 */
@QuarkusTest
class ServiceAccountSettingsResourceTest {
    private static final String REALM_DEFAULTS = "/api/admin/service-accounts/settings";
    private static final String ONE = "/api/admin/organisations/{org}/service-accounts/{sa}/settings";
    private static final String OWNER = "realm-owner";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject ServiceAccountService serviceAccountService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        orgService.create(testRealm.name(), new CreateOrgRequest("acme", Set.of(), null, null));
        orgService.create(testRealm.name(), new CreateOrgRequest("globex", Set.of(), null, null));
        serviceAccountService.create(testRealm.name(), "acme", new CreateServiceAccountRequest("batch", null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void withoutATokenGets401() {
        given().when().get(REALM_DEFAULTS).then().statusCode(401);
        given().contentType(ContentType.JSON).body(body(true)).when().put(REALM_DEFAULTS).then().statusCode(401);
        given().when().get(ONE, "acme", "batch").then().statusCode(401);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "batch").then().statusCode(401);
    }

    // the org admins neither see nor change the mode, even the admin of the org of the service account
    @Test
    @TestSecurity(user = "bob", roles = "org-acme:org-admin")
    void adminOfTheOrgGets403() {
        assertForbidden();
    }

    @Test
    @TestSecurity(user = "bob", roles = "org-acme:org-owner")
    void ownerOfTheOrgGets403() {
        assertForbidden();
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    void adminOfAnotherOrgGets403() {
        assertForbidden();
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void orgUserGets403() {
        assertForbidden();
    }

    // the token of a service account carries no org role
    @Test
    @TestSecurity(user = "service-account-org-acme.batch", roles = {})
    void serviceAccountTokenGets403() {
        assertForbidden();
    }

    // a lightweight token is refused by the filter, before the roles (401 with its reason)
    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    @OidcSecurity(claims = @Claim(key = "lightweight", value = "true", type = ClaimType.BOOLEAN))
    void lightweightTokenOfTheRealmOwnerIsRefused() {
        given().when().get(REALM_DEFAULTS).then().statusCode(401);
        given().contentType(ContentType.JSON).body(body(true)).when().put(REALM_DEFAULTS).then().statusCode(401);
        given().when().get(ONE, "acme", "batch").then().statusCode(401);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "batch").then().statusCode(401);
        assertNothingWritten();
    }

    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void realmDefaultIsFalseOnANewRealmThenGivesTheValueBack() {
        given().when().get(REALM_DEFAULTS).then().statusCode(200).body("lightweightTokens", equalTo(false));

        given().contentType(ContentType.JSON).body(body(true)).when().put(REALM_DEFAULTS)
                .then().statusCode(200).body("lightweightTokens", equalTo(true));
        given().when().get(REALM_DEFAULTS).then().statusCode(200).body("lightweightTokens", equalTo(true));

        given().contentType(ContentType.JSON).body(body(false)).when().put(REALM_DEFAULTS)
                .then().statusCode(200).body("lightweightTokens", equalTo(false));
        given().when().get(REALM_DEFAULTS).then().statusCode(200).body("lightweightTokens", equalTo(false));
    }

    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void serviceAccountModeCanBeReadAndSwitched() {
        given().when().get(ONE, "acme", "batch").then().statusCode(200).body("lightweightTokens", equalTo(false));

        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "batch")
                .then().statusCode(200).body("lightweightTokens", equalTo(true));
        given().when().get(ONE, "acme", "batch").then().statusCode(200).body("lightweightTokens", equalTo(true));
        assertEquals("true", testRealm.resource().clients().findByClientId("org-acme.batch").getFirst()
                .getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));

        given().contentType(ContentType.JSON).body(body(false)).when().put(ONE, "acme", "batch")
                .then().statusCode(200).body("lightweightTokens", equalTo(false));
    }

    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void missingBodyOrFieldGets400() {
        given().contentType(ContentType.JSON).when().put(REALM_DEFAULTS).then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}").when().put(REALM_DEFAULTS).then().statusCode(400);
        given().contentType(ContentType.JSON).when().put(ONE, "acme", "batch").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}").when().put(ONE, "acme", "batch").then().statusCode(400);
        // nothing written
        assertNull(testRealm.resource().clients().findByClientId("org-acme.batch").getFirst()
                .getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
    }

    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void invalidNameGets400() {
        given().when().get(ONE, "acme", "Not_A_Slug").then().statusCode(400);
        given().when().get(ONE, "ACME", "batch").then().statusCode(400);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "Not_A_Slug").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void unknownOrgOrServiceAccountOrOneOfAnotherOrgGets404() {
        given().when().get(ONE, "unknown", "batch").then().statusCode(404);
        given().when().get(ONE, "acme", "unknown").then().statusCode(404);
        // "batch" exists in acme only
        given().when().get(ONE, "globex", "batch").then().statusCode(404);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "unknown", "batch").then().statusCode(404);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "unknown").then().statusCode(404);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "globex", "batch").then().statusCode(404);
    }

    // a client created by hand in the console with a matching clientId is not a service account of Orgkeep
    @Test
    @TestSecurity(user = "alice", roles = OWNER)
    void clientCreatedByHandIsNeverSwitched() {
        String uuid = testRealm.createClient("org-acme.byhand");

        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "byhand").then().statusCode(404);

        Map<String, String> attributes = testRealm.resource().clients().get(uuid).toRepresentation().getAttributes();
        assertNull(attributes == null ? null : attributes.get(LightweightTokens.CLIENT_ATTRIBUTE));
    }

    private void assertForbidden() {
        given().when().get(REALM_DEFAULTS).then().statusCode(403);
        given().contentType(ContentType.JSON).body(body(true)).when().put(REALM_DEFAULTS).then().statusCode(403);
        given().when().get(ONE, "acme", "batch").then().statusCode(403);
        given().contentType(ContentType.JSON).body(body(true)).when().put(ONE, "acme", "batch").then().statusCode(403);
        assertNothingWritten();
    }

    /** Neither the client attribute of the service account nor the realm attribute was written. */
    private void assertNothingWritten() {
        Map<String, String> clientAttributes = testRealm.resource().clients().findByClientId("org-acme.batch")
                .getFirst().getAttributes();
        assertNull(clientAttributes == null ? null : clientAttributes.get(LightweightTokens.CLIENT_ATTRIBUTE));
        Map<String, String> realmAttributes = testRealm.resource().toRepresentation().getAttributes();
        assertNull(realmAttributes == null ? null : realmAttributes.get(LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE));
    }

    private static UpdateServiceAccountSettingsRequest body(boolean lightweight) {
        return new UpdateServiceAccountSettingsRequest(lightweight);
    }
}
