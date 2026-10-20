package io.orgkeep.dashboard.organisation;

/**
 * A member of an organisation. Copy of OrgMember of orgkeep-core.
 *
 * @param role ORG_OWNER, ORG_ADMIN or ORG_USER: a String, the dashboard only passes it on to the front
 */
public record OrgMember(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        String role) { }
