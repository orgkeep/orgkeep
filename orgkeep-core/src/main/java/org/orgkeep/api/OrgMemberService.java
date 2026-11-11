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
     *
     * @throws org.orgkeep.api.exceptions.MemberAlreadyExistsException if the user is already a member
     * @throws org.orgkeep.api.exceptions.MemberOfAnotherOrgException if the user is a member of another organisation
     * @throws org.orgkeep.api.exceptions.PrivilegedUserException if the user holds a realm role of Orgkeep or a role
     *         of the realm-management client
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the organisation is disabled, also when it was
     *         disabled while the user was being added (the membership is then undone)
     */
    OrgMember add(String realm, String orgName, AddMemberRequest request);

    /**
     * Removes the user from the organisation, from every group of the organisation
     * and from the roles of the org client. The user itself is kept.
     *
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the user is an owner of the organisation
     */
    void remove(String realm, String orgName, String userId);

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
