package org.orgkeep.api;

import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgMember;

import java.util.List;

/**
 * Members of an organisation, as managed by its org admins.
 */
public interface OrgMemberService {

    /** @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name */
    List<OrgMember> list(String realm, String orgName);

    /**
     * Adds the user with this email to the organisation (creating the user if needed)
     * and to the default group /{org}/user-groups/users.
     * <p>
     * An organisation disabled while the user is being added: if the disable counted the user among its members, the
     * add goes through and the member returned is disabled (enabled again with the org); otherwise the membership is
     * undone and the call refused. Within one instance of the core, the disable runs entirely before or after the
     * add; the first case only happens with a disable from another instance. A user created by a refused call is kept, and has already been sent the
     * invitation email (UserCreated): a retry reuses it.
     * <p>
     * An existing user who is disabled (removed from its organisation while disabled) is enabled again.
     *
     * @throws org.orgkeep.api.exceptions.MemberAlreadyExistsException if the user is already a member
     * @throws org.orgkeep.api.exceptions.MemberOfAnotherOrgException if the user is a member of another organisation
     * @throws org.orgkeep.api.exceptions.PrivilegedUserException if the user holds a realm role of Orgkeep or a role
     *         of the realm-management client
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the organisation is disabled, also when it was
     *         disabled while the user was being added (the membership is then undone)
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation or on
     *         the user, after a short wait
     */
    OrgMember add(String realm, String orgName, AddMemberRequest request);

    /**
     * Removes the user from the organisation, from every group of the organisation
     * and from the roles of the org client. The user itself is kept. A member disabled with the organisation is
     * enabled again and leaves its list of disabled members: it belongs to no organisation any more.
     *
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the user is an owner of the organisation
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation or on
     *         the user, after a short wait
     */
    void remove(String realm, String orgName, String userId);

    /**
     * Disables or enables a member. Disabling also closes their sessions: refresh tokens stop working, an access
     * token already issued stays valid until it expires (token introspection sees it as inactive at once). Idempotent.
     * <p>
     * Refused, in this order: not a member of this organisation (404, whichever organisation the user is in), the
     * caller itself, an owner of the organisation, a privileged user, then, under the locks, an organisation that is
     * disabled or still being enabled again (both directions: the member is then disabled with it, or about to be
     * enabled with it).
     * <p>
     * Only {@code enabled} of the user counts: a user disabled in the Keycloak console looks the same, and can be
     * enabled again here.
     *
     * @param callerId the id (sub) of the user making the call
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the user is the caller, an owner, a
     *         privileged user, or the organisation is disabled
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation or on
     *         the user, after a short wait
     */
    OrgMember setEnabled(String realm, String orgName, String userId, boolean enabled, String callerId);

    /**
     * Emails the invitation again: a new link to choose a password (expired link, SMTP server configured after
     * the creation of the member).
     *
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the user already has a password,
     *         or the realm has no SMTP server
     */
    void resendInvitation(String realm, String orgName, String userId);
}
