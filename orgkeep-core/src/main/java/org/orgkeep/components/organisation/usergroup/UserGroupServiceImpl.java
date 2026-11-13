package org.orgkeep.components.organisation.usergroup;

import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.UserGroupAlreadyExistsException;
import org.orgkeep.api.exceptions.UserGroupNotFoundException;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.UserGroup;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.usergroup.team.TeamMapper;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class UserGroupServiceImpl implements UserGroupService {
    private final KeycloakOrgs orgs;
    private final KeycloakGroups groups;
    private final CatalogSetup catalogSetup;
    private final UserGroupMapper userGroupMapper;
    private final TeamMapper teamMapper;
    private final ResourceLocks locks;

    /** One call for the groups, then two per group: its members and its role mappings. */
    @Override
    public List<UserGroup> list(String realm, String orgName) {
        orgs.getByName(realm, orgName);
        Set<String> catalogs = catalogSetup.catalogScopeIds(realm).keySet();
        return groups.children(realm, userGroupsId(realm, orgName)).stream()
                .sorted(Comparator.comparing(GroupRepresentation::getName))
                .map(group -> {
                    Map<String, List<String>> attributes = group.getAttributes() == null ? Map.of() : group.getAttributes();
                    Map<String, Set<String>> clientRoles = groups.clientRoles(realm, group.getId());
                    return userGroupMapper.toUserGroup(group, groups.memberIds(realm, group.getId()),
                            teamMapper.toTeamProducts(attributes, clientRoles),
                            teamMapper.toTeamCatalogRoles(attributes, clientRoles, catalogs));
                })
                .toList();
    }

    @Override
    public UserGroup create(String realm, String orgName, CreateUserGroupRequest request) {
        // a short write: ORG shared (refused while the org is being disabled or deleted), the team exclusively (two
        // creations of the same name do not both pass the check below)
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, request.name(), LockMode.EXCLUSIVE))) {
            orgs.getByName(realm, orgName);
            if (groups.findId(realm, OrgGroups.userGroup(orgName, request.name())).isPresent()) {
                throw new UserGroupAlreadyExistsException(orgName, request.name());
            }

            String groupId = groups.createChild(realm, userGroupsId(realm, orgName), request.name());
            log.info("User group '{}' created in organisation '{}' of realm '{}'", request.name(), orgName, realm);
            // a new group grants nothing and has no member
            return new UserGroup(groupId, request.name(), false, Set.of(), List.of(), List.of());
        }
    }

    @Override
    public void delete(String realm, String orgName, String groupName) {
        // the team exclusively: no write on its attributes or members runs while it is deleted
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, groupName, LockMode.EXCLUSIVE))) {
            orgs.getByName(realm, orgName);
            if (OrgGroups.isPredefined(groupName)) {
                throw new OperationNotAllowedException(
                        "The user group '%s' comes with the organisation and cannot be deleted".formatted(groupName));
            }
            groups.delete(realm, groupId(realm, orgName, groupName));
            log.info("User group '{}' deleted from organisation '{}' of realm '{}'", groupName, orgName, realm);
        }
    }

    @Override
    public void addMember(String realm, String orgName, String groupName, String userId) {
        // ORG shared (refused while the org is being disabled or deleted), the team shared (its attributes are not
        // written meanwhile), the user exclusively (a user being removed from the org must not join a team of it)
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, groupName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            String groupId = groupId(realm, orgName, groupName);
            // a group of the org only takes members of the org: the products it grants are those of the org
            if (!orgs.isMember(realm, org.getId(), userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }

            // joining a group the user is already in changes nothing in Keycloak
            groups.join(realm, userId, groupId);
        }
    }

    @Override
    public void removeMember(String realm, String orgName, String groupName, String userId) {
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, groupName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
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
    }

    /** The parent group /{org}/user-groups, created with the organisation. */
    private String userGroupsId(String realm, String orgName) {
        return groups.findId(realm, OrgGroups.userGroups(orgName))
                .orElseThrow(() -> new IllegalStateException(
                        "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.userGroups(orgName))));
    }

    /** The org must exist, checked by the caller: an unknown org answers "unknown org", not "unknown group". */
    private String groupId(String realm, String orgName, String groupName) {
        if (!OrgGroups.isUserGroupName(groupName)) {
            throw new UserGroupNotFoundException(orgName, groupName);
        }
        return groups.findId(realm, OrgGroups.userGroup(orgName, groupName))
                .orElseThrow(() -> new UserGroupNotFoundException(orgName, groupName));
    }
}
