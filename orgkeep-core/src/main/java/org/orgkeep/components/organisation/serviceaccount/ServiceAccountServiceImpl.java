package org.orgkeep.components.organisation.serviceaccount;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.ServiceAccountAlreadyExistsException;
import org.orgkeep.api.exceptions.ServiceAccountNotFoundException;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.ServiceAccountCredentials;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.UpdateServiceAccountRequest;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.components.organisation.member.MemberEligibility;
import org.orgkeep.components.organisation.usergroup.team.TeamAlignment;
import org.orgkeep.components.realm.lightweight.LightweightClients;
import org.orgkeep.config.KeycloakUrls;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service accounts of an org: see ServiceAccounts for how one is told from another client. This service never reads
 * the token of the caller.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class ServiceAccountServiceImpl implements ServiceAccountService {
    /**
     * The only default client scopes of a service account (besides the catalogs it is given): "basic" (sub), "roles"
     * (resource_access, and the audience: the catalog client introspects the tokens it is the audience of) and
     * "service_account" (client_id...), plus "organization" (set apart below). Every other default scope of the realm is
     * removed: no profile, no email.
     */
    private static final Set<String> KEPT_DEFAULT_SCOPES = Set.of("basic", "roles", "service_account");
    /** The scope of the native mapper of the claim "organization": a default scope of the client, not an optional one. */
    private static final String ORGANIZATION_SCOPE = "organization";

    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final KeycloakOrgs orgs;
    private final KeycloakClientScopes scopes;
    private final KeycloakRealms realms;
    private final LightweightClients lightweightClients;
    private final CatalogSetup catalogSetup;
    private final TeamAlignment teamAlignment;
    private final ServiceAccounts serviceAccounts;
    private final MemberEligibility eligibility;
    private final ResourceLocks locks;
    private final KeycloakUrls urls;
    private final ServiceAccountMapper mapper;

    /**
     * A short write: ORG is locked shared, compatible with other creations but refused (409, after a short wait) while
     * a heavy operation holds the org: a disable or a deletion in progress. The unicity of the name is Keycloak's, the
     * clientId is unique.
     */
    @Override
    public ServiceAccountCreated create(String realm, String orgName, CreateServiceAccountRequest request) {
        ServiceAccounts.requireName(request.name());
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED))) {
            return createLocked(realm, orgName, request);
        }
    }

    /** All the checks come before the first write, the writes are rolled back by hand (the admin API is not transactional). */
    private ServiceAccountCreated createLocked(String realm, String orgName, CreateServiceAccountRequest request) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        requireEnabledAndSettled(org);
        List<String> defaultCatalogs = request.defaultCatalogs() == null ? List.of() : request.defaultCatalogs();
        Map<String, String> catalogScopeIds = checkDefaultCatalogs(realm, orgName, defaultCatalogs);
        // the default of the realm, read under the lock before the first write: absent means full tokens
        boolean lightweight = realms.attribute(realm, LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE)
                .map(Boolean::parseBoolean)
                .orElse(false);

        String clientUuid = clients.create(realm, mapper.toClient(orgName, request))
                .orElseThrow(() -> new ServiceAccountAlreadyExistsException(orgName, request.name()));
        try {
            clients.reduceScopes(realm, clientUuid, KEPT_DEFAULT_SCOPES);
            // after reduceScopes, which would remove the marker scope
            if (lightweight) {
                lightweightClients.apply(realm, clientUuid, true);
            }
            catalogSetup.setDefaultCatalogs(realm, clientUuid, catalogScopeIds, defaultCatalogs);
            // the claim "organization" comes from the native mapper of this scope, as for a user: it needs the user to be
            // a member of the Keycloak organisation (Keycloak refuses to introspect a token with that claim otherwise)
            String organizationScopeId = scopes.findId(realm, ORGANIZATION_SCOPE).orElseThrow(() ->
                    new IllegalStateException("Realm '%s' has no client scope '%s'".formatted(realm, ORGANIZATION_SCOPE)));
            clients.makeDefaultScope(realm, clientUuid, organizationScopeId);

            UserRepresentation user = clients.serviceAccountUser(realm, clientUuid).orElseThrow(() ->
                    new IllegalStateException("Client '%s' has no service account user".formatted(request.name())));
            // in the group first: from the moment the user is a member of the Keycloak organisation, everything that
            // lists the members must already know it is not a person (see ServiceAccounts.isServiceAccountUser)
            groups.join(realm, user.getId(), serviceAccountsGroupId(realm, orgName));
            // an UNMANAGED member, like the users added by an org admin. Deleting the client removes both memberships
            orgs.addMember(realm, org.getId(), user.getId());

            String secret = clients.regenerateSecret(realm, clientUuid);

            // Read again once the user is in the group (see MemberEligibility.afterJoin for the members). A disable
            // of the org from another instance (the lock does not reach it) may have run before the user was in the
            // group, so it did not see it. On one instance, the lock prevents this case
            requireEnabledAndSettled(orgs.getByName(realm, orgName));

            ClientRepresentation client = clients.get(realm, clientUuid);
            log.info("Service account '{}' created in organisation '{}' of realm '{}'", request.name(), orgName, realm);
            return new ServiceAccountCreated(
                    toServiceAccount(realm, orgName, client, user, catalogScopeIds),
                    new ServiceAccountCredentials(client.getClientId(), secret, urls.token(realm)));
        } catch (RuntimeException e) {
            log.warn("Creation of service account '{}' in organisation '{}' of realm '{}' failed, rolling back",
                    request.name(), orgName, realm);
            rollback(realm, clientUuid, e);
            throw e;
        }
    }

    /** Deleting the client also deletes its user and its group membership. The group of the org is kept. */
    private void rollback(String realm, String clientUuid, RuntimeException cause) {
        try {
            clients.delete(realm, clientUuid);
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /**
     * The id of /{org}/service-accounts. Orgs created before the service accounts do not have it: created at the
     * first service account (no catch-up at startup).
     */
    private String serviceAccountsGroupId(String realm, String orgName) {
        return groups.findId(realm, OrgGroups.serviceAccounts(orgName)).orElseGet(() -> {
            String rootId = groups.findId(realm, OrgGroups.root(orgName)).orElseThrow(() -> new IllegalStateException(
                    "Organisation '%s' has no group %s".formatted(orgName, OrgGroups.root(orgName))));
            // empty if a concurrent creation just made it: read it back
            return groups.createChildIfAbsent(realm, rootId, OrgGroups.SERVICE_ACCOUNTS, Map.of())
                    .or(() -> groups.findId(realm, OrgGroups.serviceAccounts(orgName)))
                    .orElseThrow(() -> new IllegalStateException(
                            "Group %s not found".formatted(OrgGroups.serviceAccounts(orgName))));
        });
    }

    /**
     * One call for the group, one for the catalogs, then a client and its scopes per member: the admin API has no way
     * to fetch them at once. Acceptable, an org has few service accounts.
     */
    @Override
    public List<ServiceAccount> list(String realm, String orgName) {
        orgs.getByName(realm, orgName);
        List<UserRepresentation> members = serviceAccounts.users(realm, orgName);
        if (members.isEmpty()) {
            return List.of();
        }
        Map<String, String> catalogScopeIds = catalogSetup.catalogScopeIds(realm);
        return members.stream()
                .flatMap(user -> serviceAccounts.clientOf(realm, orgName, user)
                        .map(client -> toServiceAccount(realm, orgName, client, user, catalogScopeIds))
                        .stream())
                .sorted(Comparator.comparing(ServiceAccount::name))
                .toList();
    }

    @Override
    public ServiceAccount get(String realm, String orgName, String name) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved resolved = resolve(realm, orgName, name);
        return toServiceAccount(realm, orgName, resolved.client(), resolved.user(), catalogSetup.catalogScopeIds(realm));
    }

    /** A short write: ORG shared and the user of the service account exclusive, like a change on a member. */
    @Override
    public ServiceAccount update(String realm, String orgName, String name, UpdateServiceAccountRequest request) {
        List<String> defaultCatalogs = request.defaultCatalogs() == null ? List.of() : request.defaultCatalogs();
        ServiceAccounts.Resolved first = firstResolve(realm, orgName, name);
        try (HeldLocks ignored = lockServiceAccount(realm, orgName, first)) {
            ServiceAccounts.Resolved resolved = resolve(realm, orgName, name);
            Map<String, String> catalogScopeIds = checkDefaultCatalogs(realm, orgName, defaultCatalogs);

            String clientUuid = resolved.client().getId();
            ClientRepresentation client = clients.get(realm, clientUuid);
            client.setDescription(request.description());
            clients.update(realm, clientUuid, client);
            // a catalog leaving the list goes back to optional, it is not removed
            catalogSetup.setDefaultCatalogs(realm, clientUuid, catalogScopeIds, defaultCatalogs);

            log.info("Service account '{}' of organisation '{}' updated in realm '{}'", name, orgName, realm);
            return toServiceAccount(realm, orgName, clients.get(realm, clientUuid), resolved.user(), catalogScopeIds);
        }
    }

    /** Allowed while the service account or the org is disabled. The old secret stops working at once. */
    @Override
    public ServiceAccountCredentials regenerateSecret(String realm, String orgName, String name) {
        ServiceAccounts.Resolved first = firstResolve(realm, orgName, name);
        try (HeldLocks ignored = lockServiceAccount(realm, orgName, first)) {
            ServiceAccounts.Resolved resolved = resolve(realm, orgName, name);
            String secret = clients.regenerateSecret(realm, resolved.client().getId());
            log.info("Secret of service account '{}' of organisation '{}' regenerated in realm '{}'", name, orgName, realm);
            return new ServiceAccountCredentials(resolved.client().getClientId(), secret, urls.token(realm));
        }
    }

    /**
     * Same rules as OrgMemberServiceImpl.setEnabled. The cheap refusals come first, without a lock: the org and the
     * service account (404). Then the locks, and what a concurrent change may have altered is read again: while the
     * org is disabled, or while its list of disabled members is not empty (an enable that did not finish), the call
     * is refused in both directions. The org is never written here: the service account would have to enter or leave
     * that list, which means rewriting the whole org under ORG exclusive.
     * <p>
     * No check of the caller: a service account never calls this endpoint, its token carries no org role.
     */
    @Override
    public ServiceAccount setEnabled(String realm, String orgName, String name, boolean enabled) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved first = resolve(realm, orgName, name);
        try (HeldLocks ignored = lockServiceAccount(realm, orgName, first)) {
            requireEnabledAndSettled(orgs.getByName(realm, orgName));
            // a delete may have passed between the first check and the locks
            ServiceAccounts.Resolved resolved = resolve(realm, orgName, name);

            String userId = resolved.user().getId();
            users.setEnabled(realm, userId, enabled);
            if (!enabled) {
                // harmless: a client credentials grant has no refresh token by default. An access token already
                // issued lives until it expires
                users.logout(realm, userId);
            }
            log.info("Service account '{}' of organisation '{}' in realm '{}' {}", name, orgName, realm,
                    enabled ? "enabled" : "disabled");
            // built under the lock from what was resolved and written, like update
            return toServiceAccount(realm, orgName, resolved.client(), users.get(realm, userId),
                    catalogSetup.catalogScopeIds(realm));
        }
    }

    /**
     * ORG exclusive, short wait: the id of the service account may have to leave the list of the disabled members,
     * which rewrites the org. Deleting the client deletes its user and its memberships. The id is removed from the
     * list afterwards: a stale id left by a failure here is skipped when the org is enabled ("a user deleted in the
     * meantime").
     */
    @Override
    public void delete(String realm, String orgName, String name) {
        orgs.getByName(realm, orgName);
        resolve(realm, orgName, name);
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.EXCLUSIVE))) {
            ServiceAccounts.Resolved resolved = resolve(realm, orgName, name);
            clients.delete(realm, resolved.client().getId());

            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            if (DisabledMembers.remove(org, List.of(resolved.user().getId()))) {
                orgs.update(realm, org);
            }
            log.info("Service account '{}' of organisation '{}' deleted from realm '{}'", name, orgName, realm);
        }
    }

    /**
     * Locks taken for a change on one service account: ORG shared, then the service-account user exclusive, as for a
     * change on a member.
     */
    private HeldLocks lockServiceAccount(String realm, String orgName, ServiceAccounts.Resolved resolved) {
        return locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, resolved.user().getId(), LockMode.EXCLUSIVE));
    }

    /** The first resolution, before the locks: it gives the id of the user to lock. The org comes first (404). */
    private ServiceAccounts.Resolved firstResolve(String realm, String orgName, String name) {
        orgs.getByName(realm, orgName);
        return resolve(realm, orgName, name);
    }

    private ServiceAccounts.Resolved resolve(String realm, String orgName, String name) {
        return serviceAccounts.resolve(realm, orgName, name);
    }

    /** Nobody is created or changed in a disabled org, or one whose members are not all enabled again yet. */
    private void requireEnabledAndSettled(OrganizationRepresentation org) {
        if (!org.isEnabled()) {
            throw MemberEligibility.disabledOrg(org.getName());
        }
        if (!DisabledMembers.of(org).isEmpty()) {
            throw new OperationNotAllowedException(
                    "The organisation '%s' is disabled or being enabled again".formatted(org.getName()));
        }
    }

    /**
     * The catalogs of a request: each must exist and hold at least one product of the org (400).
     *
     * @return every catalog of the realm, name → scope id, as CatalogSetup.setDefaultCatalogs wants them
     */
    private Map<String, String> checkDefaultCatalogs(String realm, String orgName, List<String> defaultCatalogs) {
        Map<String, String> catalogScopeIds = catalogSetup.catalogScopeIds(realm);
        Set<String> wanted = new LinkedHashSet<>(defaultCatalogs);
        List<String> unknown = wanted.stream().filter(name -> !catalogScopeIds.containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Not catalogs of realm '%s': %s".formatted(realm, unknown));
        }
        List<String> notAssigned = wanted.stream()
                .filter(name -> !teamAlignment.hasAssignment(realm, orgName, name))
                .toList();
        if (!notAssigned.isEmpty()) {
            throw new InvalidRequestException(
                    "The organisation '%s' has no product in the catalogs: %s".formatted(orgName, notAssigned));
        }
        return catalogScopeIds;
    }

    private ServiceAccount toServiceAccount(String realm, String orgName, ClientRepresentation client,
                                            UserRepresentation user, Map<String, String> catalogScopeIds) {
        Set<String> defaultScopeIds = clients.defaultScopeIds(realm, client.getId());
        List<String> defaultCatalogs = catalogScopeIds.entrySet().stream()
                .filter(catalog -> defaultScopeIds.contains(catalog.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return mapper.toServiceAccount(ServiceAccounts.nameOf(orgName, client.getClientId()), client, user,
                defaultCatalogs, serviceAccounts.teamsOf(realm, orgName, user.getId()));
    }
}
