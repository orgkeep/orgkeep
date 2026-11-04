package org.orgkeep.dashboard.auth;

import org.orgkeep.web.security.OrgRoles;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * The org roles of a browser session, for the endpoints of the org admins in the core.
 * <p>
 * The core reads them in resource_access of the principal (OrgRolesAugmentor of orgkeep-core). With a bearer token,
 * the principal is the access token, which has the claim. With a session, the principal is the ID token, and
 * Keycloak only puts client roles in the access token: without this augmentor, an org admin would get 403.
 * <p>
 * The access token of the session is verified by Quarkus (quarkus.oidc.dashboard.authentication.verify-access-token):
 * reading its claims here trusts a checked signature, not a value sent by the browser (the session cookie is
 * encrypted by Quarkus anyway).
 */
@ApplicationScoped
public class SessionOrgRolesAugmentor implements SecurityIdentityAugmentor {

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        // only a session has an ID token: bearer identities are left to the augmentor of the core
        AccessTokenCredential accessToken = identity.getCredential(AccessTokenCredential.class);
        if (identity.isAnonymous() || identity.getCredential(IdTokenCredential.class) == null || accessToken == null) {
            return Uni.createFrom().item(identity);
        }
        Object resourceAccess = claims(accessToken.getToken()).get("resource_access");
        Set<String> orgRoles = OrgRoles.fromResourceAccess(resourceAccess instanceof JsonObject json ? json : null);
        if (orgRoles.isEmpty()) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRoles(orgRoles).build());
    }

    /** The payload of a JWT, its middle part: base64url encoded JSON. The signature is checked by Quarkus. */
    private static JsonObject claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
