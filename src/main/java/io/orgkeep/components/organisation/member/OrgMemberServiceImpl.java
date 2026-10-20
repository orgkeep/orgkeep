package io.orgkeep.components.organisation.member;

import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.events.UserCreated;
import io.orgkeep.api.exceptions.MemberAlreadyExistsException;
import io.orgkeep.api.exceptions.MemberNotFoundException;
import io.orgkeep.api.exceptions.MemberOfAnotherOrgException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.OrgClients;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgMember;
import io.orgkeep.api.model.OrgRole;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import io.orgkeep.repository.keycloak.KeycloakUsers;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.List;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class OrgMemberServiceImpl implements OrgMemberService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final MemberMapper memberMapper;
    private final EventBus eventBus;

    @Override
    public List<OrgMember> list(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);

        // three calls whatever the number of members, rather than the roles of each member:
        // the owners (role granted directly) and the admins (members of the admin group)
        Set<String> ownerIds = clients.findUuid(realm, OrgClients.clientId(orgName))
                .map(clientUuid -> clients.usersWithRole(realm, clientUuid, OrgRole.ORG_OWNER.getRoleName()))
                .orElse(Set.of());
        Set<String> adminIds = groups.findId(realm, OrgGroups.admin(orgName))
                .map(adminGroupId -> groups.memberIds(realm, adminGroupId))
                .orElse(Set.of());

        return orgs.members(realm, org.getId()).stream()
                .map(member -> memberMapper.toOrgMember(member, roleOf(member.getId(), ownerIds, adminIds)))
                .toList();
    }

    /** The highest role wins: an owner who is also in the admin group is shown as owner. */
    private static OrgRole roleOf(String userId, Set<String> ownerIds, Set<String> adminIds) {
        if (ownerIds.contains(userId)) {
            return OrgRole.ORG_OWNER;
        }
        return adminIds.contains(userId) ? OrgRole.ORG_ADMIN : OrgRole.ORG_USER;
    }

    @Override
    public OrgMember add(String realm, String orgName, AddMemberRequest request) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);

        String userId = users.findIdByEmail(realm, request.email())
                .orElseGet(() -> createUser(realm, orgName, request));
        List<String> userOrgs = orgs.organisationsOf(realm, userId);
        if (userOrgs.contains(orgName)) {
            throw new MemberAlreadyExistsException(orgName, request.email());
        }
        if (!userOrgs.isEmpty()) {
            throw new MemberOfAnotherOrgException(request.email(), userOrgs.get(0));
        }
        if (!orgs.addMember(realm, org.getId(), userId)) {
            throw new MemberAlreadyExistsException(orgName, request.email());
        }

        String usersGroupId = groups.findId(realm, OrgGroups.users(orgName))
                .orElseThrow(() -> new IllegalStateException(
                        "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.users(orgName))));
        groups.join(realm, userId, usersGroupId);

        log.info("User {} added to organisation '{}' in realm '{}'", request.email(), orgName, realm);
        // a new member only joined the users group
        return memberMapper.toOrgMember(users.get(realm, userId), OrgRole.ORG_USER);
    }

    @Override
    public void remove(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!orgs.isMember(realm, org.getId(), userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }

        clients.findUuid(realm, OrgClients.clientId(orgName)).ifPresent(clientUuid -> {
            if (clients.directRolesOf(realm, clientUuid, userId).contains(OrgRole.ORG_OWNER.getRoleName())) {
                throw new OperationNotAllowedException("An owner of the organisation '%s' cannot be removed".formatted(orgName));
            }
            // direct roles of the org client, if any were granted outside of the groups
            clients.revokeAllFromUser(realm, clientUuid, userId);
        });

        // Keycloak only removes the organisation membership: the org groups (and the roles and products
        // they grant) must be left explicitly, otherwise the user would keep their access
        String orgPrefix = OrgGroups.root(orgName) + "/";
        groups.groupsOf(realm, userId).stream()
                .filter(group -> group.getPath().startsWith(orgPrefix))
                .forEach(group -> groups.leave(realm, userId, group.getId()));

        orgs.removeMember(realm, org.getId(), userId);
        log.info("User {} removed from organisation '{}' in realm '{}'", userId, orgName, realm);
    }

    private String createUser(String realm, String orgName, AddMemberRequest request) {
        String userId = users.create(realm, memberMapper.toUserRepresentation(request));
        // no password: the invitation email lets the member choose one
        eventBus.publish(UserCreated.ADDRESS, new UserCreated(realm, orgName, userId, false));
        return userId;
    }
}
