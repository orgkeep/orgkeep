package org.orgkeep.api;

import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.UserGroup;

import java.util.List;

/**
 * User groups of an organisation (/{org}/user-groups/*), managed by its org admins: they create groups
 * and put members in them. Later, these groups (the teams) will give the products of the org to their members.
 */
public interface UserGroupService {

    /** @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name */
    List<UserGroup> list(String realm, String orgName);

    /**
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.UserGroupAlreadyExistsException if the org has a group with this name
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the org or the group
     */
    UserGroup create(String realm, String orgName, CreateUserGroupRequest request);

    /**
     * Deletes the group: its members lose the products it granted.
     *
     * @throws org.orgkeep.api.exceptions.UserGroupNotFoundException if the org has no group with this name
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException for a predefined group (admin, users)
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the org or the group
     */
    void delete(String realm, String orgName, String groupName);

    /**
     * Puts a member of the organisation in the group. Idempotent.
     *
     * @throws org.orgkeep.api.exceptions.UserGroupNotFoundException if the org has no group with this name
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member of the organisation
     */
    void addMember(String realm, String orgName, String groupName, String userId);

    /**
     * Takes a member out of the group. Idempotent.
     *
     * @throws org.orgkeep.api.exceptions.UserGroupNotFoundException if the org has no group with this name
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException for the group users: every member stays in it
     */
    void removeMember(String realm, String orgName, String groupName, String userId);
}
