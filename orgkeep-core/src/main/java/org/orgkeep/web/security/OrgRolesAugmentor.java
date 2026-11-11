package org.orgkeep.web.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;

/**
 * Adds the org client roles of the token to the SecurityIdentity (see {@link OrgRoles}).
 * <p>
 * Quarkus calls every SecurityIdentityAugmentor bean once per authenticated request, after the token
 * is verified: a close equivalent of a Spring Security JwtAuthenticationConverter.
 */
@ApplicationScoped
public class OrgRolesAugmentor implements SecurityIdentityAugmentor {

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        // anonymous requests and @TestSecurity identities carry no JWT: nothing to add
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
            return Uni.createFrom().item(identity);
        }
        Object resourceAccess = jwt.getClaim("resource_access");
        Set<String> orgRoles = OrgRoles.fromResourceAccess(resourceAccess instanceof JsonObject json ? json : null);
        if (orgRoles.isEmpty()) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRoles(orgRoles).build());
    }
}
