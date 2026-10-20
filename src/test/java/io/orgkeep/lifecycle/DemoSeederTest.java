package io.orgkeep.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    Map.entry("orgkeep.demo.enabled", "true"));
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

    @Test
    void dashboardClientIsReadyForTheDashboardInDev() {
        ClientRepresentation client = keycloak.realm(REALM).clients()
                .findByClientId(DemoSeeder.DASHBOARD_CLIENT_ID).get(0);

        assertFalse(client.isPublicClient());
        assertTrue(client.isStandardFlowEnabled());
        assertEquals(List.of("http://localhost:8284/*"), client.getRedirectUris());
        assertEquals(DemoSeeder.DASHBOARD_SECRET,
                keycloak.realm(REALM).clients().get(client.getId()).getSecret().getValue());
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

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) {
            if (value.equals(element.asText())) {
                return true;
            }
        }
        return false;
    }
}
