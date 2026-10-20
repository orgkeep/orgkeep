package io.orgkeep.web.security;

import io.orgkeep.api.model.OrgRole;
import io.orgkeep.api.model.RealmRole;
import io.quarkus.security.PermissionChecker;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Permissions that depend on the organisation of the request, used with
 * {@code @PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)}.
 * <p>
 * Quarkus passes the endpoint parameter with the same name ("org") to the checker:
 * the counterpart of a Spring {@code @PreAuthorize("@orgPermissions.canAdminister(#org)")}.
 */
@ApplicationScoped
public class OrgPermissions {
    public static final String ADMINISTER_ORG = "administer-org";

    /**
     * Org admins (and owners, org-owner includes org-admin) of this org, and the realm owner.
     * <p>
     * Public, not package-private: Quarkus calls it from a class it generates at build time. When the core is
     * embedded as a jar (orgkeep-dashboard), that class and this one are not in the same class loader, and a
     * package-private checker silently denies every request (403, nothing logged).
     */
    @PermissionChecker(ADMINISTER_ORG)
    public boolean canAdminister(SecurityIdentity identity, String org) {
        return identity.hasRole(RealmRole.REALM_OWNER.getRoleName())
                || identity.hasRole(OrgRoles.qualified(org, OrgRole.ORG_ADMIN));
    }
}
