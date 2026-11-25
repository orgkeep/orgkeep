package org.orgkeep.api;

import org.orgkeep.api.model.OrgMember;

/**
 * Ownership of an organisation, changed by the realm owner only: an owner is a plain member who holds the client
 * role org-owner directly on the client org-{org}, not through a group. An owner has no right an org admin lacks,
 * but cannot be removed or disabled: only the realm owner can revoke ownership first.
 */
public interface OrgOwnerService {

    /**
     * Makes an existing person member of the organisation its owner: the client role org-owner is granted directly,
     * then the member leaves the default group /{org}/user-groups/users if it was in it (without that, it would
     * drop out of the unfiltered members list; the owners' list already skips a member who is also in that group).
     * Idempotent: a member already an owner only has the group left again, if a previous call stopped in between.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name, or it has no client
     *         org-{org} (created outside Orgkeep: there is no owner role to grant)
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a person member of this
     *         organisation (a service account included, or a member of another organisation)
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the member is disabled by its own org
     *         admin (it would then be an owner nobody could enable again), or is a privileged user (a realm role of
     *         Orgkeep or a role of the realm-management client), with a message that does not name the role
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation or on
     *         the user, after a short wait
     */
    OrgMember grant(String realm, String orgName, String userId);

    /**
     * Revokes the ownership of a member: the member joins /{org}/user-groups/users if it was not in it already,
     * then the direct client role org-owner is removed from the client org-{org}, so it becomes a plain member who
     * can then be removed or disabled by its org admins. Its other groups and teams, admin included, are left
     * untouched.
     * Idempotent: a member who is not an owner only joins the group if missing from it, and revoking an organisation
     * down to zero owners is allowed.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name, or it has no client
     *         org-{org} (created outside Orgkeep)
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a person member of this
     *         organisation (a service account included, or a member of another organisation)
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation or on
     *         the user, after a short wait
     */
    void revoke(String realm, String orgName, String userId);
}
