package org.orgkeep.components.organisation.suspension;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.AccountSuspensionService;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.Suspensions;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.components.organisation.member.MemberEligibility;
import org.orgkeep.components.organisation.member.MemberMapper;
import org.orgkeep.components.organisation.member.invitation.InvitationMailer;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccountMapper;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Suspensions set and lifted by the realm owner. Reuses the checks of the members and service accounts services
 * (a service account user is never a person, see {@link #isPerson}; {@link MemberEligibility#privilegedAmong}),
 * and builds its own OrgMember / ServiceAccount, the way setEnabled / the service accounts service do: this
 * service never reads the caller's token (the resource passes suspenderId in).
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class AccountSuspensionServiceImpl implements AccountSuspensionService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final ServiceAccounts serviceAccounts;
    private final MemberEligibility eligibility;
    private final MemberMapper memberMapper;
    private final ServiceAccountMapper serviceAccountMapper;
    private final InvitationMailer invitations;
    private final CatalogSetup catalogSetup;
    private final ResourceLocks locks;

    // ---- members

    @Override
    public OrgMember suspendMember(String realm, String orgName, String userId, String reason, String suspenderId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        // a neutral message: it does not say which role of the realm the user holds (same rule as add / setEnabled)
        if (!eligibility.privilegedAmong(realm, List.of(userId)).isEmpty()) {
            throw new OperationNotAllowedException("This member cannot be suspended");
        }

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation current = orgs.getByName(realm, orgName);
            if (!isPerson(realm, current, userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }
            suspend(realm, current, userId, reason, suspenderId);
        }
        return memberOf(realm, orgName, userId);
    }

    @Override
    public OrgMember liftMember(String realm, String orgName, String userId) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (!isPerson(realm, org, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, userId, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation current = orgs.getByName(realm, orgName);
            if (!isPerson(realm, current, userId)) {
                throw new MemberNotFoundException(orgName, userId);
            }
            lift(realm, current, userId);
        }
        return memberOf(realm, orgName, userId);
    }

    /**
     * A member of the org who is a person: a service account is a member of the Keycloak organisation too, and
     * every member endpoint answers as if it were not (404). Duplicated from Members.isPerson (package-private,
     * in organisation.member): the same two calls, since this service lives in a different package.
     */
    private boolean isPerson(String realm, OrganizationRepresentation org, String userId) {
        return orgs.isMember(realm, org.getId(), userId) && !serviceAccounts.isServiceAccountUser(realm, org.getName(), userId);
    }

    /** One member, built like OrgMemberServiceImpl.setEnabled builds it: role, invitationPending, suspension. */
    private OrgMember memberOf(String realm, String orgName, String userId) {
        UserRepresentation user = users.get(realm, userId);
        OrgRole role = roleOf(realm, orgName, userId);
        return memberMapper.toOrgMember(user, role, invitations.isPending(user, role));
    }

    /** The highest role Orgkeep grants this single user: owner (direct role), admin (in the admin group), or user. */
    private OrgRole roleOf(String realm, String orgName, String userId) {
        boolean owner = clients.findUuid(realm, OrgClients.clientId(orgName))
                .map(clientUuid -> clients.directRolesOf(realm, clientUuid, userId).contains(OrgRole.ORG_OWNER.getRoleName()))
                .orElse(false);
        if (owner) {
            return OrgRole.ORG_OWNER;
        }
        boolean admin = groups.findId(realm, OrgGroups.admin(orgName))
                .map(adminGroupId -> groups.memberIds(realm, adminGroupId).contains(userId))
                .orElse(false);
        return admin ? OrgRole.ORG_ADMIN : OrgRole.ORG_USER;
    }

    // ---- service accounts

    @Override
    public ServiceAccount suspendServiceAccount(String realm, String orgName, String name, String reason, String suspenderId) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved first = serviceAccounts.resolve(realm, orgName, name);

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, first.user().getId(), LockMode.EXCLUSIVE))) {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            ServiceAccounts.Resolved resolved = serviceAccounts.resolve(realm, orgName, name);
            suspend(realm, org, resolved.user().getId(), reason, suspenderId);
            return serviceAccountOf(realm, orgName, resolved);
        }
    }

    @Override
    public ServiceAccount liftServiceAccount(String realm, String orgName, String name) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved first = serviceAccounts.resolve(realm, orgName, name);

        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, first.user().getId(), LockMode.EXCLUSIVE))) {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            ServiceAccounts.Resolved resolved = serviceAccounts.resolve(realm, orgName, name);
            lift(realm, org, resolved.user().getId());
            return serviceAccountOf(realm, orgName, resolved);
        }
    }

    /** One service account, built like ServiceAccountServiceImpl builds it. */
    private ServiceAccount serviceAccountOf(String realm, String orgName, ServiceAccounts.Resolved resolved) {
        ClientRepresentation client = resolved.client();
        Map<String, String> catalogScopeIds = catalogSetup.catalogScopeIds(realm);
        Set<String> defaultScopeIds = clients.defaultScopeIds(realm, client.getId());
        List<String> defaultCatalogs = catalogScopeIds.entrySet().stream()
                .filter(catalog -> defaultScopeIds.contains(catalog.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        UserRepresentation user = users.get(realm, resolved.user().getId());
        return serviceAccountMapper.toServiceAccount(ServiceAccounts.nameOf(orgName, client.getClientId()), client,
                user, defaultCatalogs, serviceAccounts.teamsOf(realm, orgName, user.getId()));
    }

    // ---- shared suspend / lift, under the locks of the caller

    /**
     * Writes the suspension record (every attribute at a first suspension, only the reason on an already
     * suspended account), guards against a realm whose user profile does not declare the attributes, then
     * disables the account if it is enabled and ends its sessions. Resumable: a failure after the record is
     * written leaves the account possibly still enabled, and the same call finishes the job (the record is found,
     * the reason rewritten, then the account disabled and logged out).
     */
    private void suspend(String realm, OrganizationRepresentation org, String userId, String reason, String suspenderId) {
        UserRepresentation user = users.get(realm, userId);
        Optional<SuspensionAttributes.SuspensionRecord> existing = SuspensionAttributes.of(user);
        if (existing.isEmpty()) {
            SuspensionAttributes.OrgChoice orgChoice =
                    Boolean.FALSE.equals(user.isEnabled()) && !DisabledMembers.of(org).contains(userId)
                            ? SuspensionAttributes.OrgChoice.DISABLED
                            : SuspensionAttributes.OrgChoice.ENABLED;
            users.setAttributes(realm, userId, SuspensionAttributes.newSuspension(reason, suspenderId, orgChoice));
        } else {
            users.setAttributes(realm, userId, SuspensionAttributes.reasonOnly(reason));
        }

        UserRepresentation written = users.get(realm, userId);
        if (SuspensionAttributes.of(written).isEmpty()) {
            log.error("Realm '{}' does not declare the suspension attributes in its user profile: user {} left "
                    + "untouched. The realm was created before this feature and must be recreated (no migration in "
                    + "0.x)", realm, userId);
            throw new IllegalStateException(("Realm '%s' does not declare the suspension attributes in its user "
                    + "profile: it was created before this feature and must be recreated (no migration in 0.x)")
                    .formatted(realm));
        }

        if (Boolean.TRUE.equals(user.isEnabled())) {
            users.setEnabled(realm, userId, false);
        }
        // the refresh tokens stop working; an access token already issued lives until it expires
        users.logout(realm, userId);
    }

    /**
     * No record: idempotent, nothing to do. Enables the account again only if the organisation is enabled, the
     * account is not listed as disabled by it, and the organisation's choice was "enabled"; otherwise it stays
     * disabled. Enabled first, then the attributes removed: a failure in between leaves an enabled account that
     * still carries its record, and the same call finishes the job (the reverse order would leave a disabled
     * account indistinguishable from one disabled by an org admin). Never writes the organisation.
     */
    private void lift(String realm, OrganizationRepresentation org, String userId) {
        UserRepresentation user = users.get(realm, userId);
        Optional<SuspensionAttributes.SuspensionRecord> existing = SuspensionAttributes.of(user);
        if (existing.isEmpty()) {
            return;
        }
        boolean targetEnabled = org.isEnabled() && !DisabledMembers.of(org).contains(userId)
                && existing.get().orgChoice() == SuspensionAttributes.OrgChoice.ENABLED;
        if (targetEnabled) {
            users.setEnabled(realm, userId, true);
        }
        users.removeAttributes(realm, userId, Suspensions.ATTRIBUTE_NAMES);
    }
}
