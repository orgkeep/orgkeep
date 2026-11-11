package org.orgkeep.web.security;

import org.orgkeep.api.model.OrgRole;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit test (no @QuarkusTest): parsing of the resource_access claim of a Keycloak token.
 */
class OrgRolesTest {

    @Test
    void keepsOnlyOrgClientRoles() {
        // shape of the claim in a Keycloak access token
        JsonObject resourceAccess = Json.createObjectBuilder()
                .add("org-acme", Json.createObjectBuilder()
                        .add("roles", Json.createArrayBuilder().add("org-admin").add("org-user")))
                .add("org-globex", Json.createObjectBuilder()
                        .add("roles", Json.createArrayBuilder().add("org-user")))
                .add("account", Json.createObjectBuilder()
                        .add("roles", Json.createArrayBuilder().add("manage-account")))
                .build();

        assertEquals(Set.of("org-acme:org-admin", "org-acme:org-user", "org-globex:org-user"),
                OrgRoles.fromResourceAccess(resourceAccess));
    }

    @Test
    void missingClaimGivesNoRole() {
        assertEquals(Set.of(), OrgRoles.fromResourceAccess(null));
    }

    @Test
    void qualifiedRoleMatchesTheParsedForm() {
        assertEquals("org-acme:org-admin", OrgRoles.qualified("acme", OrgRole.ORG_ADMIN));
    }
}
