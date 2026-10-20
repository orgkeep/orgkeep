package io.orgkeep.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Client roles created on the client of each organisation (see {@link OrgClients}).
 * Composite: org-owner includes org-admin, which includes org-user.
 */
@AllArgsConstructor
public enum OrgRole {
    ORG_OWNER("org-owner"),
    ORG_ADMIN("org-admin"),
    ORG_USER("org-user");

    @Getter
    private final String roleName;

}
