package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgOwnerService;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class OrgOwnerServiceImpl implements OrgOwnerService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final MemberEligibility eligibility;
    private final MemberMapper memberMapper;
    private final ResourceLocks locks;
    private final ServiceAccounts serviceAccounts;

    /**
     * Checks come first, without a lock (cheapest first): the organisation, its client org-{org} (missing for an
     * organisation created outside Orgkeep: there is no owner role to grant), then the membership. Then ORG shared
     * and USER(id) exclusive, a short wait, under which the organisation and the membership are read again (a
     * remove may have passed in between), followed by the member disabled and privileged checks.
     * <p>
     * The role is granted before the member leaves the default group users: if the call stops in between, the
     * member is both an owner and in users, shown once in the members list (as an owner, which the group already
     * skips), and replaying the same call finishes the job.
     */
    @Override
    public OrgMember grant(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        String clientUuid = orgClientUuid(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation current = orgs.getByName(realm, orgName);
            if (!isPerson(realm, current, userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }
            UserRepresentation user = users.get(realm, userId);
            // a member disabled by a disabled organisation (listed in disabled-members) is not refused: it is
            // enabled again with the org, like any other listed member
            if (Boolean.FALSE.equals(user.isEnabled()) && !DisabledMembers.of(current).contains(userId)) {
                throw new OperationNotAllowedException(
                        "This member is disabled: enable them before making them an owner");
            }
            // a neutral message: it does not say which role of the realm the user holds
            if (!eligibility.privilegedAmong(realm, List.of(userId)).isEmpty()) {
                throw new OperationNotAllowedException("This member cannot be made an owner");
            }

            clients.grantToUser(realm, clientUuid, userId, OrgRole.ORG_OWNER.getRoleName());
            groups.findId(realm, OrgGroups.users(orgName)).ifPresent(groupId -> groups.leave(realm, userId, groupId));

            log.info("Member {} of organisation '{}' in realm '{}' made owner", userId, orgName, realm);
            // invitationPending is always false for an owner, as in the owners of the organisation lists and details
            return memberMapper.toOrgMember(user, OrgRole.ORG_OWNER, Boolean.FALSE);
        }
    }

    /**
     * Same checks as {@link #grant}, without the disabled or privileged member refusals: a revoke only ever lowers
     * what the member can do. The member joins users before the role is removed: if the call stops in between, it
     * is both an owner and in users, shown once (as an owner), and replaying the same call finishes the job.
     */
    @Override
    public void revoke(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        String clientUuid = orgClientUuid(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation current = orgs.getByName(realm, orgName);
            if (!isPerson(realm, current, userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }

            // an organisation created outside Orgkeep may have no group users: skip the join, as listNoFilter does
            groups.findId(realm, OrgGroups.users(orgName)).ifPresent(groupId -> groups.join(realm, userId, groupId));
            clients.revokeFromUser(realm, clientUuid, userId, OrgRole.ORG_OWNER.getRoleName());

            log.info("Member {} of organisation '{}' in realm '{}' is no longer an owner", userId, orgName, realm);
        }
    }

    /** @throws OrgNotFoundException if no organisation has this name, or it has no client org-{org} */
    private String orgClientUuid(String realm, String orgName) {
        return clients.findUuid(realm, OrgClients.clientId(orgName))
                .orElseThrow(() -> new OrgNotFoundException(realm, orgName));
    }

    private boolean isPerson(String realm, OrganizationRepresentation org, String userId) {
        return Members.isPerson(orgs, serviceAccounts, realm, org, userId);
    }
}
