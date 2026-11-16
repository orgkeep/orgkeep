package org.orgkeep.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess;

import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production path: the realm does not exist at the start, the runtime admin client is the service account of that
 * realm (client credentials), and only the bootstrap credentials of master can create the realm. A successful start
 * means OnPremiseBootstrap created the realm through master, then went on as the service account. Then the main
 * areas of the core are used through that same client.
 * <p>
 * A QuarkusTestProfile restarts the application for this class (see DemoSeederTest): keep such classes few.
 */
@QuarkusTest
@TestProfile(ServiceAccountPathTest.ServiceAccountProfile.class)
class ServiceAccountPathTest {
    private static final String REALM = "sa-path";
    private static final String CLIENT_ID = "orgkeep-admin";
    private static final String SECRET = "orgkeep-admin-dev-secret";
    private static final String ORG = "acme";

    public static class ServiceAccountProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("orgkeep.realm-name", REALM),
                    Map.entry("quarkus.keycloak.devservices.realm-name", REALM),
                    Map.entry("quarkus.keycloak.admin-client.realm", REALM),
                    Map.entry("quarkus.keycloak.admin-client.grant-type", "CLIENT_CREDENTIALS"),
                    Map.entry("quarkus.keycloak.admin-client.client-id", CLIENT_ID),
                    Map.entry("quarkus.keycloak.admin-client.client-secret", SECRET),
                    Map.entry("orgkeep.keycloak.client-secret", SECRET));
        }
    }

    @Inject Keycloak keycloak;
    @Inject KeycloakAdminAccess adminAccess;
    @Inject RealmProvisioner realmService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;
    @Inject ServiceAccountService serviceAccountService;

    @AfterAll
    static void deleteTheRealm() {
        String url = ConfigProvider.getConfig().getValue("quarkus.keycloak.admin-client.server-url", String.class);
        try (Keycloak master = KeycloakBuilder.builder().serverUrl(url).realm("master").clientId("admin-cli")
                .grantType(OAuth2Constants.PASSWORD).username("admin").password("admin").build()) {
            master.realm(REALM).remove();
        } catch (WebApplicationException ignored) {
            // the application did not start far enough to create it
        }
    }

    @Test
    void theRuntimeClientIsTheServiceAccountOfTheRealm() throws Exception {
        assertEquals(REALM, adminAccess.realm());
        assertEquals(KeycloakAdminAccess.Probe.OK, adminAccess.probe());

        String token = keycloak.tokenManager().getAccessTokenString();
        var claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals(CLIENT_ID, claims.path("azp").asText());
        assertTrue(claims.path("iss").asText().endsWith("/realms/" + REALM), claims.toString());
        assertTrue(realmService.exists(REALM));
    }

    @Test
    void theCoreWorksAsTheServiceAccount() {
        // smtp
        realmService.configureSmtp(REALM, new SmtpSettings("mailpit", 1025, "no-reply@orgkeep.test", null, null, null,
                false, false));

        // catalog, product, org, assignment
        catalogService.create(REALM, new CreateCatalogRequest("prod", "Production", null));
        productService.create(REALM, "prod", new CreateProductRequest("billing", "Billing", null));
        orgService.create(REALM, new CreateOrgRequest(ORG, Set.of(), "boss@acme.test", "password", "Bob", "Durand"));
        productService.assign(REALM, ORG, "prod", "billing", new AssignProductRequest(1000L, QuotaCycle.MONTHLY));

        // a member, invited
        memberService.add(REALM, ORG, new AddMemberRequest("claire@acme.test", "Claire", "Petit"));
        assertFalse(memberService.list(REALM, ORG).isEmpty());

        // a team and what it gives
        userGroupService.create(REALM, ORG, new CreateUserGroupRequest("sales"));
        teamService.grantProduct(REALM, ORG, "sales", "prod", "billing", GrantProductRequest.allFeatures());

        // a service account of the org
        var created = serviceAccountService.create(REALM, ORG, new CreateServiceAccountRequest("ci", null, null));
        assertNotNull(created);
        assertEquals(1, serviceAccountService.list(REALM, ORG).size());
    }
}
