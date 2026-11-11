package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgService;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.ClientAlreadyExistsException;
import org.orgkeep.api.exceptions.OrgAlreadyExistsException;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateOrgResponse;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.OrgDetails;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.api.model.OrgSummary;
import org.orgkeep.api.model.CatalogRoles;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import org.orgkeep.components.organisation.member.DisabledMembers;
import org.orgkeep.components.organisation.member.MemberEligibility;
import org.orgkeep.components.organisation.member.MemberMapper;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class OrgServiceImpl implements OrgService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final MemberEligibility eligibility;
    private final OrgLocks orgLocks;
    private final CatalogSetup catalogSetup;
    private final EventBus eventBus;
    private final OrgMapper orgMapper;
    private final MemberMapper memberMapper;

    /**
     * Creates the Keycloak organisation, a client "org-{name}" holding the org roles,
     * the org group tree (see {@link OrgGroups}), and, if an owner email is given,
     * makes that user a member with the org-owner role.
     */
    @Override
    public CreateOrgResponse create(String realm, CreateOrgRequest request) {
        // checked before anything is created: an existing user may be privileged or already belong to another org.
        // A member of an org with this very name is not refused there: the org already exists, reported below as such
        if (request.ownerEmail() != null) {
            users.findIdByEmail(realm, request.ownerEmail())
                    .ifPresent(userId -> eligibility.checkReusable(realm, request.name(), userId, request.ownerEmail()));
        }

        String orgId = orgs.create(realm, orgMapper.toOrgRepresentation(request))
                .orElseThrow(() -> new OrgAlreadyExistsException(realm, request.name()));
        String clientUuid = null;
        String groupId = null;
        String ownerId = null;
        try {
            clientUuid = createOrgClient(realm, request);
            groupId = createOrgGroups(realm, request.name(), clientUuid);
            if (request.ownerEmail() != null) {
                ownerId = assignOwner(realm, orgId, clientUuid, request);
            }
        } catch (RuntimeException e) {
            // admin API calls are not transactional: undo the org, its client and its groups so that a retry starts clean.
            // A newly created owner user is kept, a retry will simply reuse it.
            log.warn("Provisioning of organisation '{}' in realm '{}' failed, rolling back", request.name(), realm);
            rollback(realm, orgId, clientUuid, groupId, e);
            throw e;
        }

        log.info("Organisation '{}' created in realm '{}'", request.name(), realm);
        return new CreateOrgResponse(orgId, request.name(), request.emailDomains(),
                OrgClients.clientId(request.name()), clientUuid, groupId, ownerId);
    }

    /**
     * One search call, then four calls per organisation (org client, owners, member count, product groups):
     * the admin API has no way to fetch them for several orgs at once. Acceptable for a page of at most 100 orgs.
     */
    @Override
    public List<OrgSummary> list(String realm, String search, String catalog, String product, int first, int max) {
        if (isBlank(catalog) && isBlank(product)) {
            return orgs.search(realm, search, first, max).stream()
                    .map(org -> toSummary(realm, org))
                    .toList();
        }
        // Keycloak cannot search organisations by group: the names come from the assignment groups,
        // then the search and the page are applied here, on the names sorted like the Keycloak search does
        return orgNamesWithAssignment(realm, catalog, product).stream()
                .filter(name -> search == null || name.contains(search.toLowerCase()))
                .sorted()
                .skip(first)
                .limit(max)
                .map(name -> orgs.findByName(realm, name))
                // a product group left behind by an org deleted outside of Orgkeep
                .flatMap(Optional::stream)
                .map(org -> toSummary(realm, org))
                .toList();
    }

    /**
     * The orgs whose assignment group /{org}/catalogs/{catalog}/{product} holds the product role.
     * Either filter may be absent: every catalog, or every product of the catalog. An unknown one matches nothing.
     */
    private Set<String> orgNamesWithAssignment(String realm, String catalog, String product) {
        Set<String> catalogNames = isBlank(catalog) ? catalogSetup.catalogScopeIds(realm).keySet() : Set.of(catalog);
        Set<String> names = new HashSet<>();
        for (String catalogName : catalogNames) {
            Optional<String> catalogUuid = clients.findUuid(realm, catalogName);
            if (catalogUuid.isEmpty()) {
                continue;
            }
            List<String> products = clients.listRoles(realm, catalogUuid.get()).stream()
                    .filter(role -> CatalogRoles.TYPE_PRODUCT.equals(CatalogRoles.typeOf(role.getAttributes())))
                    .map(RoleRepresentation::getName)
                    .filter(name -> isBlank(product) || name.equals(product))
                    .toList();
            for (String productName : products) {
                clients.groupPathsWithRole(realm, catalogUuid.get(), productName).stream()
                        .map(path -> OrgGroups.orgOfAssignment(path, catalogName, productName))
                        .filter(Objects::nonNull)
                        .forEach(names::add);
            }
        }
        return names;
    }

    private OrgSummary toSummary(String realm, OrganizationRepresentation org) {
        // client and groups are looked up rather than assumed: an org created outside Orgkeep has neither
        List<OrgMember> owners = clients.findUuid(realm, OrgClients.clientId(org.getName()))
                .map(clientUuid -> owners(realm, clientUuid))
                .orElse(List.of());
        // one call for the catalogs of the org, then one per catalog for its products
        List<String> products = groups.findId(realm, OrgGroups.catalogs(org.getName()))
                .map(catalogsGroupId -> groups.children(realm, catalogsGroupId))
                .orElse(List.of())
                .stream()
                .flatMap(catalogGroup -> groups.children(realm, catalogGroup.getId()).stream()
                        .map(group -> catalogGroup.getName() + "/" + group.getName()))
                .sorted()
                .toList();
        return orgMapper.toOrgSummary(org, owners, orgs.memberCount(realm, org.getId()), products);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public OrgDetails get(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        String clientId = OrgClients.clientId(orgName);

        // client and group are looked up rather than assumed: an org created outside Orgkeep has neither
        String clientUuid = clients.findUuid(realm, clientId).orElse(null);
        List<OrgMember> owners = clientUuid == null ? List.of() : owners(realm, clientUuid);
        String groupId = groups.findId(realm, OrgGroups.root(orgName)).orElse(null);

        return new OrgDetails(org.getId(), org.getName(), orgMapper.toDomainNames(org.getDomains()), org.isEnabled(),
                clientId, clientUuid, groupId, owners);
    }

    /** Users holding org-owner directly on the org client, sorted by username. */
    private List<OrgMember> owners(String realm, String clientUuid) {
        return clients.listUsersWithRole(realm, clientUuid, OrgRole.ORG_OWNER.getRoleName()).stream()
                .map(user -> memberMapper.toOrgMember(user, OrgRole.ORG_OWNER))
                .sorted(Comparator.comparing(OrgMember::username))
                .toList();
    }

    /**
     * The organisation is always marked first, its members follow. If a call fails half way, the org is disabled with
     * some members still enabled, or enabled with some members still disabled; a new call with the same value
     * finishes the job.
     * <p>
     * One call at a time per organisation, within this instance (see OrgLocks): each call reads the org and writes it
     * back whole, two of them at the same time would overwrite each other.
     */
    @Override
    public OrgDetails setEnabled(String realm, String orgName, boolean enabled) {
        orgLocks.run(realm, orgName, () -> {
            if (enabled) {
                enable(realm, orgName);
            } else {
                disable(realm, orgName);
            }
        });
        return get(realm, orgName);
    }

    /**
     * Keycloak 26 lets the members of a disabled organisation log in, with their roles: they are disabled one by one.
     * <p>
     * The org is marked disabled <b>before</b> its members are read: a member added at the same time is either read
     * here, or added after this read and then refused by OrgMemberServiceImpl.add, which checks the org again once the
     * user is a member (MemberEligibility.afterJoin).
     * <p>
     * No early return when the org is already disabled: a call that failed half way left members enabled, the next one
     * disables them. On a fully disabled org, the only enabled members left are the privileged ones: nothing changes.
     */
    private void disable(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (org.isEnabled()) {
            org.setEnabled(false);
            orgs.update(realm, org);
        }

        List<MemberRepresentation> toDisable = membersToDisable(realm, org);
        // recorded before they are disabled: after a failure half way, each member already disabled is listed and is
        // enabled again with the org. A listed member not disabled yet is simply enabled again, as it was
        addDisabledMembers(realm, orgName, toDisable.stream().map(MemberRepresentation::getId).toList());
        for (MemberRepresentation member : toDisable) {
            users.setEnabled(realm, member.getId(), false);
            // the refresh tokens stop working; an access token already issued lives until it expires
            users.logout(realm, member.getId());
        }
        log.info("Organisation '{}' disabled in realm '{}' ({} member(s) disabled)", orgName, realm, toDisable.size());
    }

    /**
     * The enabled members of the org, except the privileged ones (see MemberEligibility): a privileged member cannot
     * join an org through Orgkeep, but may have been added before, in the console, or got its role afterwards, and
     * disabling it could lock the realm owner out. A member already disabled (for another reason) is left out, and
     * stays disabled when the org is enabled again.
     */
    private List<MemberRepresentation> membersToDisable(String realm, OrganizationRepresentation org) {
        List<MemberRepresentation> enabledMembers = orgs.members(realm, org.getId()).stream()
                .filter(member -> Boolean.TRUE.equals(member.isEnabled()))
                .toList();
        Set<String> privileged = eligibility.privilegedAmong(realm,
                enabledMembers.stream().map(MemberRepresentation::getId).toList());

        List<MemberRepresentation> toDisable = new ArrayList<>();
        for (MemberRepresentation member : enabledMembers) {
            if (privileged.contains(member.getId())) {
                log.warn("User {} is privileged: left enabled with the disabled organisation '{}' in realm '{}'",
                        member.getEmail(), org.getName(), realm);
                continue;
            }
            toDisable.add(member);
        }
        return toDisable;
    }

    /**
     * The org is marked enabled <b>before</b> its members are enabled again: after a failure half way, some members
     * of an enabled org are still disabled, never the reverse (enabled members in a disabled org). The list of the
     * disabled members is kept until they are all enabled again: the next call finishes the job, even if the org is
     * already enabled.
     * <p>
     * The org is read again before the list is dropped. A disable from another instance of the core (the lock does not
     * reach it) may have run in between: it found these members still disabled, so it did not list them again, and
     * this call has just enabled them. Then the list is kept and the disable steps run again.
     */
    private void enable(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        List<String> disabled = DisabledMembers.of(org);
        if (org.isEnabled() && disabled.isEmpty()) {
            return;
        }
        if (!org.isEnabled()) {
            org.setEnabled(true);
            orgs.update(realm, org);
        }

        int enabledAgain = 0;
        for (String userId : disabled) {
            // a user deleted in the meantime is skipped
            if (users.find(realm, userId).isPresent()) {
                users.setEnabled(realm, userId, true);
                enabledAgain++;
            }
        }

        OrganizationRepresentation current = orgs.getByName(realm, orgName);
        if (!current.isEnabled()) {
            log.warn("Organisation '{}' in realm '{}' was disabled again while it was being enabled: its members are "
                    + "disabled again", orgName, realm);
            disable(realm, orgName);
            return;
        }
        if (DisabledMembers.remove(current, disabled)) {
            orgs.update(realm, current);
        }
        log.info("Organisation '{}' enabled in realm '{}' ({} member(s) enabled again)", orgName, realm, enabledAgain);
    }

    /**
     * Adds these users to the list kept on the org, which may already list members disabled by an earlier call.
     * The org is read again rather than reused: the admin API replaces the whole representation, this keeps the
     * window where another write could be overwritten short.
     */
    private void addDisabledMembers(String realm, String orgName, List<String> userIds) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (DisabledMembers.add(org, userIds)) {
            orgs.update(realm, org);
        }
    }

    @Override
    public void delete(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);

        orgs.delete(realm, org.getId());
        clients.findUuid(realm, OrgClients.clientId(orgName))
                .ifPresent(clientUuid -> clients.delete(realm, clientUuid));
        groups.findId(realm, OrgGroups.root(orgName)).ifPresentOrElse(
                groupId -> groups.delete(realm, groupId),
                () -> log.warn("Organisation '{}' had no group {}", orgName, OrgGroups.root(orgName)));

        log.info("Organisation '{}' deleted from realm '{}'", orgName, realm);
    }

    /** Creates the org client and its roles, returns the client internal id (not the clientId). */
    private String createOrgClient(String realm, CreateOrgRequest request) {
        String clientUuid = clients.create(realm, orgMapper.toClientRepresentation(request))
                .orElseThrow(() -> new ClientAlreadyExistsException(realm, OrgClients.clientId(request.name())));

        for (OrgRole role : OrgRole.values()) {
            clients.createRole(realm, clientUuid, role.getRoleName(), role.name());
        }
        // org-owner includes org-admin, which includes org-user: Keycloak puts the effective roles in the token,
        // so a security check only ever needs the lowest role it requires
        clients.addComposite(realm, clientUuid, OrgRole.ORG_OWNER.getRoleName(), OrgRole.ORG_ADMIN.getRoleName());
        clients.addComposite(realm, clientUuid, OrgRole.ORG_ADMIN.getRoleName(), OrgRole.ORG_USER.getRoleName());
        // the login client and the client of the API of Orgkeep only put the roles of their scope in their tokens
        // (fullScopeAllowed=false, see RealmProvisioner.ensureDefaults): the core API reads these roles. Deleting the
        // org client removes the mappings
        for (String clientId : List.of(realm, OrgkeepApiClient.CLIENT_ID)) {
            clients.findUuid(realm, clientId)
                    .ifPresent(scopedClientUuid -> clients.addClientRolesToScope(realm, scopedClientUuid, clientUuid));
        }
        return clientUuid;
    }

    /** Creates the group tree of the org, returns the id of the root group /{org}. */
    private String createOrgGroups(String realm, String orgName, String clientUuid) {
        String rootId = groups.createTopLevel(realm, orgName);
        String userGroupsId = groups.createChild(realm, rootId, OrgGroups.USER_GROUPS);
        groups.createChild(realm, rootId, OrgGroups.CATALOGS);

        String adminId = groups.createChild(realm, userGroupsId, OrgGroups.ADMIN);
        String usersId = groups.createChild(realm, userGroupsId, OrgGroups.USERS);
        clients.grantToGroup(realm, clientUuid, adminId, OrgRole.ORG_ADMIN.getRoleName());
        clients.grantToGroup(realm, clientUuid, usersId, OrgRole.ORG_USER.getRoleName());
        return rootId;
    }

    private String assignOwner(String realm, String orgId, String clientUuid, CreateOrgRequest request) {
        String ownerId = users.findIdByEmail(realm, request.ownerEmail())
                .orElseGet(() -> createOwner(realm, request));

        // Under the lock of the org (see OrgLocks): a disable of the org on this instance runs entirely before (the
        // org is seen disabled, without the owner) or entirely after (the owner is disabled and listed like any member)
        MemberEligibility.AfterJoin afterJoin = orgLocks.call(realm, request.name(), () -> {
            // the org was just created: the owner cannot be a member yet, the result can be ignored
            orgs.addMember(realm, orgId, ownerId);
            // the new org may have been disabled meanwhile (see MemberEligibility.afterJoin): the creation fails and
            // is rolled back, which removes the membership
            return eligibility.afterJoin(realm, request.name(), ownerId);
        });
        if (afterJoin == MemberEligibility.AfterJoin.DISABLED_WITH_THE_ORG) {
            // only with a disable from another instance of the core, which the lock does not reach. It listed the
            // owner, and disabled it or is about to: the rollback deletes the org and its list, so the owner would
            // stay disabled with no record. Enabled again first, as it was before
            users.setEnabled(realm, ownerId, true);
        }
        if (afterJoin != MemberEligibility.AfterJoin.ORG_ENABLED) {
            throw MemberEligibility.disabledOrg(request.name());
        }
        clients.grantToUser(realm, clientUuid, ownerId, OrgRole.ORG_OWNER.getRoleName());
        return ownerId;
    }

    private String createOwner(String realm, CreateOrgRequest request) {
        String ownerId = users.create(realm, orgMapper.toOwnerRepresentation(request));
        // published right away: if the provisioning fails after this, the user is kept and a retry reuses it,
        // without creating it again
        eventBus.publish(UserCreated.ADDRESS,
                new UserCreated(realm, request.name(), ownerId, request.ownerPassword() != null));
        return ownerId;
    }

    private void rollback(String realm, String orgId, String clientUuid, String groupId, RuntimeException cause) {
        try {
            if (groupId != null) {
                groups.delete(realm, groupId);
            }
            if (clientUuid != null) {
                clients.delete(realm, clientUuid);
            }
            orgs.delete(realm, orgId);
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
