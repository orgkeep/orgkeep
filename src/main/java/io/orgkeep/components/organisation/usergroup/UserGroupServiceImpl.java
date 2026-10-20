package io.orgkeep.components.organisation.usergroup;

import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.exceptions.MemberNotFoundException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.UserGroupAlreadyExistsException;
import io.orgkeep.api.exceptions.UserGroupNotFoundException;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.UserGroup;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class UserGroupServiceImpl implements UserGroupService {
    // same rule as CreateUserGroupRequest.name: a name read from the URL is not validated by Bean Validation,
    // and a "/" in it would point to another group of the tree
    private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private final KeycloakOrgs orgs;
    private final KeycloakGroups groups;
    private final UserGroupMapper userGroupMapper;

    /** One call for the groups, then one per group for its members. */
    @Override
    public List<UserGroup> list(String realm, String orgName) {
        orgs.getByName(realm, orgName);
        return groups.children(realm, userGroupsId(realm, orgName)).stream()
                .sorted(Comparator.comparing(GroupRepresentation::getName))
                .map(group -> userGroupMapper.toUserGroup(group, groups.memberIds(realm, group.getId())))
                .toList();
    }

    @Override
    public UserGroup create(String realm, String orgName, CreateUserGroupRequest request) {
        orgs.getByName(realm, orgName);
        if (groups.findId(realm, OrgGroups.userGroup(orgName, request.name())).isPresent()) {
            throw new UserGroupAlreadyExistsException(orgName, request.name());
        }

        String groupId = groups.createChild(realm, userGroupsId(realm, orgName), request.name());
        log.info("User group '{}' created in organisation '{}' of realm '{}'", request.name(), orgName, realm);
        // a new group grants nothing and has no member
        return new UserGroup(groupId, request.name(), false, Set.of());
    }

    @Override
    public void delete(String realm, String orgName, String groupName) {
        orgs.getByName(realm, orgName);
        if (OrgGroups.isPredefined(groupName)) {
            throw new OperationNotAllowedException(
                    "The user group '%s' comes with the organisation and cannot be deleted".formatted(groupName));
        }
        groups.delete(realm, groupId(realm, orgName, groupName));
        log.info("User group '{}' deleted from organisation '{}' of realm '{}'", groupName, orgName, realm);
    }

    @Override
    public void addMember(String realm, String orgName, String groupName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        String groupId = groupId(realm, orgName, groupName);
        // a group of the org only takes members of the org: the products it grants are those of the org
        if (!orgs.isMember(realm, org.getId(), userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }

        // joining a group the user is already in changes nothing in Keycloak
        groups.join(realm, userId, groupId);
    }

    @Override
    public void removeMember(String realm, String orgName, String groupName, String userId) {
        orgs.getByName(realm, orgName);
        if (OrgGroups.USERS.equals(groupName)) {
            // the group users gives org-user: leaving it would leave a member without any role in the org
            throw new OperationNotAllowedException(
                    "Every member stays in the user group '%s': remove the member from the organisation instead"
                            .formatted(groupName));
        }
        String groupId = groupId(realm, orgName, groupName);

        // leaving a group the user is not in changes nothing in Keycloak
        groups.leave(realm, userId, groupId);
    }

    /** The parent group /{org}/user-groups, created with the organisation. */
    private String userGroupsId(String realm, String orgName) {
        return groups.findId(realm, OrgGroups.userGroups(orgName))
                .orElseThrow(() -> new IllegalStateException(
                        "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.userGroups(orgName))));
    }

    /** The org must exist, checked by the caller: an unknown org answers "unknown org", not "unknown group". */
    private String groupId(String realm, String orgName, String groupName) {
        if (!GROUP_NAME.matcher(groupName).matches()) {
            throw new UserGroupNotFoundException(orgName, groupName);
        }
        return groups.findId(realm, OrgGroups.userGroup(orgName, groupName))
                .orElseThrow(() -> new UserGroupNotFoundException(orgName, groupName));
    }
}
