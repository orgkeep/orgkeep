package org.orgkeep.dashboard.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tells the front who is logged in, and with which roles: the front builds its menu from it.
 * <p>
 * Open to anonymous calls (@PermitAll): without a session cookie the identity is anonymous and the answer says so,
 * instead of a redirect to Keycloak that a fetch could not follow (the login page is on another origin).
 * <p>
 * Request scoped because it holds request scoped beans (the identity and the token of the current call).
 */
@Path("/me")
@RequestScoped
@RequiredArgsConstructor
public class CurrentUserResource {
    // Keycloak org clients, see OrgClients in orgkeep-core: "org-acme" holds the roles of org "acme"
    static final String ORG_CLIENT_PREFIX = "org-";

    private final SecurityIdentity identity;
    // In a web-app, an unqualified JsonWebToken is the access token (the ID token needs @IdToken).
    // Keycloak puts the profile claims (preferred_username, name, email) in both.
    private final JsonWebToken accessToken;

    @GET
    @PermitAll
    public CurrentUser me() {
        if (identity.isAnonymous()) {
            return CurrentUser.anonymous();
        }
        return new CurrentUser(
                true,
                accessToken.getClaim("preferred_username"),
                accessToken.getClaim("name"),
                accessToken.getClaim("email"),
                // realm roles, read from realm_access.roles of the access token (quarkus.oidc.roles.source)
                identity.getRoles(),
                orgRoles());
    }

    /** Roles of every org client in resource_access, by org name: Quarkus only maps those of its own client. */
    private Map<String, List<String>> orgRoles() {
        Map<String, List<String>> orgRoles = new TreeMap<>();
        if (!(accessToken.getClaim("resource_access") instanceof JsonObject resourceAccess)) {
            return orgRoles;
        }
        resourceAccess.forEach((clientId, access) -> {
            if (!clientId.startsWith(ORG_CLIENT_PREFIX) || access.getValueType() != JsonValue.ValueType.OBJECT) {
                return;
            }
            JsonArray roles = access.asJsonObject().getJsonArray("roles");
            if (roles == null) {
                return;
            }
            orgRoles.put(clientId.substring(ORG_CLIENT_PREFIX.length()),
                    roles.getValuesAs(JsonString.class).stream().map(JsonString::getString).sorted().toList());
        });
        return orgRoles;
    }

    /**
     * @param realmRoles e.g. ["realm-owner", ...]
     * @param orgRoles   org name -> roles in that org, e.g. {"acme": ["org-admin", "org-owner", "org-user"]}.
     *                   Keycloak gives the effective roles: an org owner also has org-admin and org-user.
     */
    public record CurrentUser(boolean authenticated,
                              String username,
                              String fullName,
                              String email,
                              Set<String> realmRoles,
                              Map<String, List<String>> orgRoles) {

        static CurrentUser anonymous() {
            return new CurrentUser(false, null, null, null, Set.of(), Map.of());
        }
    }
}
