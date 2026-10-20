package io.orgkeep.web.security;

import io.orgkeep.api.model.OrgClients;
import io.orgkeep.api.model.OrgRole;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.HashSet;
import java.util.Set;

/**
 * Org roles as they appear in the SecurityIdentity: "{clientId}:{role}", e.g. "org-acme:org-admin".
 * <p>
 * Keycloak puts client roles in the token under resource_access.{clientId}.roles. Quarkus OIDC only maps
 * the roles of its own client, so {@link OrgRolesAugmentor} adds those of every org client in this form.
 */
public final class OrgRoles {
    static final String SEPARATOR = ":";

    private OrgRoles() {
    }

    public static String qualified(String orgName, OrgRole role) {
        return OrgClients.clientId(orgName) + SEPARATOR + role.getRoleName();
    }

    /**
     * Qualified roles of the org clients found in a resource_access claim; other clients are ignored.
     * Public for an application embedding the core whose identities do not carry the claim in their principal
     * (orgkeep-dashboard: the principal of a browser session is the ID token).
     */
    public static Set<String> fromResourceAccess(JsonObject resourceAccess) {
        Set<String> roles = new HashSet<>();
        if (resourceAccess == null) {
            return roles;
        }
        resourceAccess.forEach((clientId, access) -> {
            if (!clientId.startsWith(OrgClients.PREFIX) || access.getValueType() != JsonValue.ValueType.OBJECT) {
                return;
            }
            JsonArray clientRoles = access.asJsonObject().getJsonArray("roles");
            if (clientRoles == null) {
                return;
            }
            clientRoles.getValuesAs(JsonString.class)
                    .forEach(role -> roles.add(clientId + SEPARATOR + role.getString()));
        });
        return roles;
    }
}
