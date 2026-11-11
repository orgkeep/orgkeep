package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.MemberAlreadyExistsException;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.components.organisation.OrgLocks;
import org.orgkeep.components.organisation.member.invitation.InvitationMailer;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class OrgMemberServiceImpl implements OrgMemberService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final MemberEligibility eligibility;
    private final MemberMapper memberMapper;
    private final InvitationMailer invitations;
    private final EventBus eventBus;
    private final OrgLocks orgLocks;

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
                .map(member -> memberMapper.toOrgMember(member, roleOf(member.getId(), ownerIds, adminIds),
                        invitations.isPending(realm, member)))
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
        // checked before the user is created: the members of a disabled org are disabled (see OrgService.setEnabled)
        eligibility.checkEnabled(org);

        Optional<String> existingId = users.findIdByEmail(realm, request.email());
        // an existing user may be privileged or a member of another org; a member of this org is refused just below
        existingId.ifPresent(userId -> eligibility.checkReusable(realm, orgName, userId, request.email()));
        String userId = existingId.orElseGet(() -> createUser(realm, orgName, request));
        // Under the lock of the org (see OrgLocks): a disable of the org on this instance runs entirely before (the
        // org is seen disabled, without the user) or entirely after (the user is disabled and listed like any member)
        orgLocks.run(realm, orgName, () -> join(realm, org, userId, request.email()));

        String usersGroupId = groups.findId(realm, OrgGroups.users(orgName))
                .orElseThrow(() -> new IllegalStateException(
                        "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.users(orgName))));
        groups.join(realm, userId, usersGroupId);

        log.info("User {} added to organisation '{}' in realm '{}'", request.email(), orgName, realm);
        // a new member only joined the users group
        UserRepresentation user = users.get(realm, userId);
        return memberMapper.toOrgMember(user, OrgRole.ORG_USER, invitations.isPending(realm, user));
    }

    @Override
    public void resendInvitation(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!orgs.isMember(realm, org.getId(), userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        invitations.resend(realm, userId);
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
        leaveDisabledMembers(realm, orgName, userId);
        log.info("User {} removed from organisation '{}' in realm '{}'", userId, orgName, realm);
    }

    /**
     * Makes the user a member, then checks the org again (see MemberEligibility.afterJoin):
     * <ul>
     *     <li>still enabled: done;</li>
     *     <li>disabled meanwhile, with the user listed in disabled-members (only with a disable from another instance
     *     of the core, which the lock does not reach): the add came first and goes through. The user is disabled and
     *     logged out here too, whatever the disable has done yet: the list is what enables it again with the org, so
     *     a listed member must never be left enabled;</li>
     *     <li>disabled without the user: the membership is undone and the add refused.</li>
     * </ul>
     */
    private void join(String realm, OrganizationRepresentation org, String userId, String email) {
        if (!orgs.addMember(realm, org.getId(), userId)) {
            throw new MemberAlreadyExistsException(org.getName(), email);
        }
        switch (eligibility.afterJoin(realm, org.getName(), userId)) {
            case ORG_ENABLED -> {
                // nothing more to do
            }
            case DISABLED_WITH_THE_ORG -> {
                users.setEnabled(realm, userId, false);
                users.logout(realm, userId);
            }
            case ORG_DISABLED -> throw undoMembership(realm, org, userId);
        }
    }

    /**
     * A member disabled with the org is enabled again and leaves the list: it belongs to no org any more, and nothing
     * else would ever enable it again. Enabled first: if the update of the list fails, a stale entry only enables
     * again a user already enabled. Under the lock of the org, which is written whole.
     */
    private void leaveDisabledMembers(String realm, String orgName, String userId) {
        orgLocks.run(realm, orgName, () -> {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            if (DisabledMembers.of(org).contains(userId)) {
                users.setEnabled(realm, userId, true);
                DisabledMembers.remove(org, List.of(userId));
                orgs.update(realm, org);
            }
        });
    }

    /**
     * The org was disabled without the new member: a member of a disabled org must not stay enabled, the membership is
     * undone (the user is kept). If that fails too, the refusal is still the error reported, with the failure attached.
     */
    private OperationNotAllowedException undoMembership(String realm, OrganizationRepresentation org, String userId) {
        OperationNotAllowedException refusal = MemberEligibility.disabledOrg(org.getName());
        try {
            orgs.removeMember(realm, org.getId(), userId);
        } catch (RuntimeException e) {
            refusal.addSuppressed(e);
            log.warn("User {} could not be removed from the disabled organisation '{}' in realm '{}' and may still be "
                    + "enabled: disable the organisation again", userId, org.getName(), realm, e);
        }
        return refusal;
    }

    private String createUser(String realm, String orgName, AddMemberRequest request) {
        String userId = users.create(realm, memberMapper.toUserRepresentation(request));
        // no password: the invitation email lets the member choose one
        eventBus.publish(UserCreated.ADDRESS, new UserCreated(realm, orgName, userId, false));
        return userId;
    }
}
