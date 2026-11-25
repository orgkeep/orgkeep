package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.MemberAlreadyExistsException;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.UserGroupNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.api.model.Page;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.member.invitation.InvitationMailer;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ResourceLocks locks;
    private final ServiceAccounts serviceAccounts;

    /**
     * Paginated, without a Keycloak call per member (see InvitationMailer.isPending): the number of calls stays
     * the same whatever the number of members. Three sources, chosen so that each page is exact (see
     * members.md): no filter reads the owners then the default group users, paged by Keycloak; search and team
     * read every matching page and are sliced here, since their results are small.
     *
     * @throws InvalidRequestException if search and team are both given
     */
    @Override
    public Page<OrgMember> list(String realm, String orgName, String search, String team, int first, int max) {
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasTeam = team != null && !team.isBlank();
        if (hasSearch && hasTeam) {
            throw new InvalidRequestException("search and team cannot be used together");
        }
        OrganizationRepresentation org = orgs.getByName(realm, orgName);

        // one call whatever the number of members, rather than the role of each member: the admins
        // (members of the admin group)
        Set<String> adminIds = groups.findId(realm, OrgGroups.admin(orgName))
                .map(adminGroupId -> groups.memberIds(realm, adminGroupId))
                .orElse(Set.of());
        // read once, by the single call that returns both the ids and the full representations, and shared by
        // the three branches below: only listNoFilter needs the full representations
        List<UserRepresentation> ownerUsers = clients.findUuid(realm, OrgClients.clientId(orgName))
                .map(clientUuid -> clients.listUsersWithRole(realm, clientUuid, OrgRole.ORG_OWNER.getRoleName()))
                .orElse(List.of());

        if (hasTeam) {
            return listByTeam(realm, orgName, team, adminIds, ownerUsers, first, max);
        }
        if (hasSearch) {
            return listBySearch(realm, org, search, adminIds, ownerUsers, first, max);
        }
        return listNoFilter(realm, org, orgName, adminIds, ownerUsers, first, max);
    }

    /**
     * Owners (sorted by username), then the members of the default group users, paged by Keycloak: that group
     * never holds a service account, and every member stays in it (UserGroupServiceImpl.removeMember), so it is
     * exact. The page is the concatenation of the two, cut at [first, first + max): with o owners, if first < o
     * the page starts at owners[first..] and continues with group members from 0, otherwise group members start
     * at first - o. A group member who is also an owner (console only) is skipped: the page is one shorter.
     * <p>
     * An organisation created outside Orgkeep may have no group "users": treated as an empty group rather than
     * failing (same pattern as {@link ServiceAccounts#users}, for its own group), so the page still shows the
     * owners.
     */
    private Page<OrgMember> listNoFilter(String realm, OrganizationRepresentation org, String orgName,
                                         Set<String> adminIds, List<UserRepresentation> ownerUsers, int first, int max) {
        Set<String> ownerIds = ownerUsers.stream().map(UserRepresentation::getId).collect(Collectors.toSet());
        List<OrgMember> owners = ownerUsers.stream()
                // owners are never pending (see members.md)
                .map(user -> memberMapper.toOrgMember(user, OrgRole.ORG_OWNER, Boolean.FALSE))
                .sorted(Comparator.comparing(OrgMember::username))
                .toList();
        Optional<String> usersGroupId = groups.findId(realm, OrgGroups.users(orgName));

        List<OrgMember> page = new ArrayList<>();
        int remaining = max;
        if (first < owners.size()) {
            List<OrgMember> ownersFromFirst = owners.subList(first, owners.size());
            int fromOwners = Math.min(remaining, ownersFromFirst.size());
            page.addAll(ownersFromFirst.subList(0, fromOwners));
            remaining -= fromOwners;
            int fromGroup = remaining;
            if (fromGroup > 0) {
                usersGroupId.ifPresent(groupId ->
                        page.addAll(groupMembers(realm, groupId, 0, fromGroup, ownerIds, adminIds)));
            }
        } else {
            int groupFirst = first - owners.size();
            int groupMax = remaining;
            usersGroupId.ifPresent(groupId ->
                    page.addAll(groupMembers(realm, groupId, groupFirst, groupMax, ownerIds, adminIds)));
        }

        long memberCount = serviceAccounts.peopleCount(realm, orgName, orgs.memberCount(realm, org.getId()));
        return new Page<>(page, memberCount);
    }

    private List<OrgMember> groupMembers(String realm, String groupId, int groupFirst, int groupMax,
                                         Set<String> ownerIds, Set<String> adminIds) {
        return groups.membersPage(realm, groupId, groupFirst, groupMax).stream()
                // a member of the group who is also an owner (added through the console) is already in the
                // page: shown once, as an owner
                .filter(user -> !ownerIds.contains(user.getId()))
                .map(user -> toOrgMember(user, roleOf(user.getId(), ownerIds, adminIds)))
                .toList();
    }

    /**
     * Every member whose username, email or first name matches, read page by page and sliced here: the search
     * results of an organisation are small enough. Service accounts never match a search by username or email
     * (see service-accounts.md), but are dropped the same way as elsewhere, by the group.
     */
    private Page<OrgMember> listBySearch(String realm, OrganizationRepresentation org, String search,
                                         Set<String> adminIds, List<UserRepresentation> ownerUsers, int first, int max) {
        Set<String> ownerIds = ownerUsers.stream().map(UserRepresentation::getId).collect(Collectors.toSet());
        Set<String> serviceAccountIds = serviceAccounts.userIds(realm, org.getName());
        List<MemberRepresentation> people = orgs.searchMembers(realm, org.getId(), search).stream()
                .filter(member -> !serviceAccountIds.contains(member.getId()))
                .toList();
        List<OrgMember> items = people.stream()
                .skip(first)
                .limit(max)
                .map(member -> toOrgMember(member, roleOf(member.getId(), ownerIds, adminIds)))
                .toList();
        return new Page<>(items, people.size());
    }

    /** Every member of the team, read whole (a team is small) and sliced here. Admin and users are accepted. */
    private Page<OrgMember> listByTeam(String realm, String orgName, String team, Set<String> adminIds,
                                       List<UserRepresentation> ownerUsers, int first, int max) {
        String groupId = teamGroupId(realm, orgName, team);
        Set<String> ownerIds = ownerUsers.stream().map(UserRepresentation::getId).collect(Collectors.toSet());
        Set<String> serviceAccountIds = serviceAccounts.userIds(realm, orgName);
        List<UserRepresentation> people = groups.membersFull(realm, groupId).stream()
                .filter(member -> !serviceAccountIds.contains(member.getId()))
                .toList();
        List<OrgMember> items = people.stream()
                .skip(first)
                .limit(max)
                .map(member -> toOrgMember(member, roleOf(member.getId(), ownerIds, adminIds)))
                .toList();
        return new Page<>(items, people.size());
    }

    /** @throws UserGroupNotFoundException if no user group of the organisation has this name */
    private String teamGroupId(String realm, String orgName, String team) {
        if (!OrgGroups.isUserGroupName(team)) {
            throw new UserGroupNotFoundException(orgName, team);
        }
        return groups.findId(realm, OrgGroups.userGroup(orgName, team))
                .orElseThrow(() -> new UserGroupNotFoundException(orgName, team));
    }

    /** The highest role wins: an owner who is also in the admin group is shown as owner. */
    private static OrgRole roleOf(String userId, Set<String> ownerIds, Set<String> adminIds) {
        if (ownerIds.contains(userId)) {
            return OrgRole.ORG_OWNER;
        }
        return adminIds.contains(userId) ? OrgRole.ORG_ADMIN : OrgRole.ORG_USER;
    }

    /**
     * Maps a member with its invitation status, the role computed once and passed to both (see
     * InvitationMailer.isPending): an owner found by listBySearch or listByTeam goes through here too (never
     * pending, excluded by its role), listNoFilter is the only one to map its owners separately, with
     * Boolean.FALSE.
     */
    private OrgMember toOrgMember(UserRepresentation user, OrgRole role) {
        return memberMapper.toOrgMember(user, role, invitations.isPending(user, role));
    }

    /**
     * A short write: ORG is locked shared from the start, before any user is created, and for the whole call (see
     * ResourceLocks). Compatible with other adds, but refused (409, after a short wait) while a heavy operation holds
     * the org exclusively: a disable or a deletion in progress. So no user is created for an org being deleted.
     * <p>
     * The email is locked exclusively too (the user may not exist yet), then the id once the user is found: two adds of
     * the same email in two organisations cannot both pass "is the user in another org?" and then join, the second
     * waits for the first (and is then refused as a member of another org) or gets a 409 after the short wait.
     */
    @Override
    public OrgMember add(String realm, String orgName, AddMemberRequest request) {
        try (HeldLocks held = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userByEmail(realm, request.email(), LockMode.EXCLUSIVE))) {
            return addLocked(realm, orgName, request, held);
        }
    }

    private OrgMember addLocked(String realm, String orgName, AddMemberRequest request, HeldLocks held) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        // checked before the user is created: the members of a disabled org are disabled (see OrgService.setEnabled)
        eligibility.checkEnabled(org);

        Optional<String> existingId = users.findIdByEmail(realm, request.email());
        // an existing user is also locked by id, the key the other writes on a user use (remove, roles, teams)
        existingId.ifPresent(id -> held.add(LockRequest.userById(realm, id, LockMode.EXCLUSIVE)));
        // an existing user may be privileged or a member of another org; a member of this org is refused just below
        existingId.ifPresent(userId -> eligibility.checkReusable(realm, orgName, userId, request.email()));
        String userId = existingId.orElseGet(() -> createUser(realm, orgName, request));
        // Under the shared lock of the org, held by add: a disable of the org on this instance runs entirely before
        // (the org is seen disabled, without the user) or entirely after (the user is disabled and listed like any
        // member)
        join(realm, org, userId, request.email(), existingId.isPresent());

        String usersGroupId = groups.findId(realm, OrgGroups.users(orgName))
                .orElseThrow(() -> new IllegalStateException(
                        "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.users(orgName))));
        groups.join(realm, userId, usersGroupId);

        log.info("User {} added to organisation '{}' in realm '{}'", request.email(), orgName, realm);
        // a new member only joined the users group
        UserRepresentation user = users.get(realm, userId);
        return toOrgMember(user, OrgRole.ORG_USER);
    }

    @Override
    public void resendInvitation(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        invitations.resend(realm, orgName, userId);
    }

    /**
     * A short write. ORG is locked shared, unless removing the member has to write the org (see
     * {@link #leaveDisabledMembers}): then exclusively. The mode is chosen on a first read of the org, which may be
     * stale (a disable may finish between that read and the lock), so the org is read again once the lock is held. If
     * the shared lock turns out not to be enough, everything is released and taken again exclusively, once: a lock
     * is never upgraded in place.
     */
    @Override
    public void remove(String realm, String orgName, String userId) {
        LockMode mode = writesTheOrg(orgs.getByName(realm, orgName), null) ? LockMode.EXCLUSIVE : LockMode.SHARED;
        // the user is locked by id, exclusively, with the org: a role or a team given to the user meanwhile waits
        HeldLocks held = locks.acquire(LockRequest.org(realm, orgName, mode),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE));
        try {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            if (mode == LockMode.SHARED && writesTheOrg(org, userId)) {
                held.close();
                held = locks.acquire(LockRequest.org(realm, orgName, LockMode.EXCLUSIVE),
                        LockRequest.userById(realm, userId, LockMode.EXCLUSIVE));
                // no new decision on a new read: under the exclusive lock the org is stable
                org = orgs.getByName(realm, orgName);
            }
            removeLocked(realm, org, userId);
        } finally {
            held.close();
        }
    }

    /**
     * The cheap refusals come first, without a lock, in the decided order: not a member (404), the caller itself, an
     * owner, a privileged user. Then ORG is locked shared and the user exclusively, and what a concurrent change may
     * have altered is read again: the state of the org and the membership.
     * <p>
     * The org is never written: while it is disabled, or while the list of its disabled members is not empty (an
     * enable that did not finish), the call is refused in both directions. Otherwise the member would have to enter
     * or leave that list, which means rewriting the whole org under ORG exclusive.
     */
    @Override
    public OrgMember setEnabled(String realm, String orgName, String userId, boolean enabled, String callerId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        // a caller without id cannot be told from the member: a programming error, the resource refuses it before
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("callerId is required");
        }
        if (userId.equals(callerId)) {
            throw new OperationNotAllowedException("You cannot change your own status");
        }
        Optional<String> clientUuid = clients.findUuid(realm, OrgClients.clientId(orgName));
        boolean owner = clientUuid.isPresent()
                && clients.directRolesOf(realm, clientUuid.get(), userId).contains(OrgRole.ORG_OWNER.getRoleName());
        if (owner) {
            throw new OperationNotAllowedException("The status of an owner of the organisation '%s' cannot be changed".formatted(orgName));
        }
        // a neutral message: it does not say which role of the realm the user holds
        if (!eligibility.privilegedAmong(realm, List.of(userId)).isEmpty()) {
            throw new OperationNotAllowedException("The status of this member cannot be changed");
        }

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation current = orgs.getByName(realm, orgName);
            if (!current.isEnabled() || !DisabledMembers.of(current).isEmpty()) {
                throw new OperationNotAllowedException(
                        "The organisation '%s' is disabled or being enabled again".formatted(orgName));
            }
            // a remove may have passed between the first check and the locks
            if (!isPerson(realm, current, userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }
            users.setEnabled(realm, userId, enabled);
            if (!enabled) {
                // the refresh tokens stop working; an access token already issued lives until it expires
                users.logout(realm, userId);
            }
            log.info("Member {} of organisation '{}' in realm '{}' {}", userId, orgName, realm,
                    enabled ? "enabled" : "disabled");
        }
        return memberOf(realm, orgName, userId);
    }

    /**
     * A member of the org who is a person: the service accounts are members of the Keycloak organisation too, and
     * every member endpoint answers as if they were not (404).
     */
    private boolean isPerson(String realm, OrganizationRepresentation org, String userId) {
        return orgs.isMember(realm, org.getId(), userId)
                && !serviceAccounts.isServiceAccountUser(realm, org.getName(), userId);
    }

    /** One member, as {@link #list} renders it. Never an owner here: setEnabled refuses owners before. */
    private OrgMember memberOf(String realm, String orgName, String userId) {
        Set<String> adminIds = groups.findId(realm, OrgGroups.admin(orgName))
                .map(adminGroupId -> groups.memberIds(realm, adminGroupId))
                .orElse(Set.of());
        UserRepresentation user = users.get(realm, userId);
        return toOrgMember(user, roleOf(userId, Set.of(), adminIds));
    }

    /**
     * Whether removing this member rewrites the org: it is disabled, or the member is listed among the members
     * disabled with it (leaveDisabledMembers). With no member (the first, possibly stale read): the org is disabled or
     * lists any disabled member.
     */
    private static boolean writesTheOrg(OrganizationRepresentation org, String userId) {
        if (!org.isEnabled()) {
            return true;
        }
        return userId == null ? !DisabledMembers.of(org).isEmpty() : DisabledMembers.of(org).contains(userId);
    }

    private void removeLocked(String realm, OrganizationRepresentation org, String userId) {
        String orgName = org.getName();
        if (!isPerson(realm, org, userId)) {
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
     *     <li>still enabled: done. An existing user who was disabled (removed from its org while disabled, or
     *     disabled in the console) is enabled again: joining an org is a decision of an org admin;</li>
     *     <li>disabled meanwhile, with the user listed in disabled-members (only with a disable from another instance
     *     of the core, which the lock does not reach): the add came first and goes through. The user is disabled and
     *     logged out here too, whatever the disable has done yet: the list is what enables it again with the org, so
     *     a listed member must never be left enabled;</li>
     *     <li>disabled without the user: the membership is undone and the add refused.</li>
     * </ul>
     */
    private void join(String realm, OrganizationRepresentation org, String userId, String email, boolean existingUser) {
        if (!orgs.addMember(realm, org.getId(), userId)) {
            throw new MemberAlreadyExistsException(org.getName(), email);
        }
        switch (eligibility.afterJoin(realm, org.getName(), userId)) {
            case ORG_ENABLED -> {
                if (existingUser) {
                    enableAgain(realm, userId);
                }
            }
            case DISABLED_WITH_THE_ORG -> {
                users.setEnabled(realm, userId, false);
                users.logout(realm, userId);
            }
            case ORG_DISABLED -> throw undoMembership(realm, org, userId);
        }
    }

    private void enableAgain(String realm, String userId) {
        if (Boolean.FALSE.equals(users.get(realm, userId).isEnabled())) {
            users.setEnabled(realm, userId, true);
            log.info("Disabled user {} enabled again by joining an organisation in realm '{}'", userId, realm);
        }
    }

    /**
     * A member disabled with the org is enabled again and leaves the list: it belongs to no org any more, and nothing
     * else would ever enable it again. Enabled first: if the update of the list fails, a stale entry only enables
     * again a user already enabled. The org is written whole: called under its exclusive lock when it has to write
     * (see {@link #remove}), under the shared one only when there is nothing to write.
     */
    private void leaveDisabledMembers(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (DisabledMembers.of(org).contains(userId)) {
            users.setEnabled(realm, userId, true);
            DisabledMembers.remove(org, List.of(userId));
            orgs.update(realm, org);
        }
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
        eventBus.publish(UserCreated.ADDRESS, new UserCreated(realm, orgName, userId, false, false));
        return userId;
    }
}
