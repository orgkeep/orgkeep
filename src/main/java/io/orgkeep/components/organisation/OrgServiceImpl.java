package io.orgkeep.components.organisation;

import io.orgkeep.api.OrgService;
import io.orgkeep.api.events.UserCreated;
import io.orgkeep.api.exceptions.ClientAlreadyExistsException;
import io.orgkeep.api.exceptions.MemberOfAnotherOrgException;
import io.orgkeep.api.exceptions.OrgAlreadyExistsException;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateOrgResponse;
import io.orgkeep.api.model.OrgClients;
import io.orgkeep.api.model.OrgkeepApiClient;
import io.orgkeep.api.model.OrgDetails;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgMember;
import io.orgkeep.api.model.OrgRole;
import io.orgkeep.api.model.OrgSummary;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import io.orgkeep.repository.keycloak.KeycloakUsers;
import io.orgkeep.components.organisation.member.MemberMapper;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        // checked before anything is created: an existing user may already belong to another org
        if (request.ownerEmail() != null) {
            users.findIdByEmail(realm, request.ownerEmail())
                    .flatMap(userId -> orgs.organisationsOf(realm, userId).stream()
                            // same name: the org already exists, reported below as such
                            .filter(orgName -> !orgName.equals(request.name()))
                            .findFirst())
                    .ifPresent(otherOrg -> {
                        throw new MemberOfAnotherOrgException(request.ownerEmail(), otherOrg);
                    });
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
     * The members disabled with the organisation are listed in this attribute of the Keycloak organisation (user ids,
     * one per value), to enable again only them. Not on the users: the user profile of Keycloak 24+ drops the user
     * attributes it does not declare.
     */
    static final String DISABLED_MEMBERS_ATTRIBUTE = "disabled-members";

    @Override
    public OrgDetails setEnabled(String realm, String orgName, boolean enabled) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        if (org.isEnabled() == enabled) {
            return get(realm, orgName);
        }

        if (enabled) {
            enableMembers(realm, org);
        } else {
            disableMembers(realm, org);
        }
        org.setEnabled(enabled);
        orgs.update(realm, org);

        log.info("Organisation '{}' {} in realm '{}'", orgName, enabled ? "enabled" : "disabled", realm);
        return get(realm, orgName);
    }

    /**
     * Keycloak 26 lets the members of a disabled organisation log in, with their roles: they are disabled one by one.
     * A member already disabled (for another reason) is left out, and stays disabled when the org is enabled again.
     */
    private void disableMembers(String realm, OrganizationRepresentation org) {
        List<String> disabled = new ArrayList<>();
        for (MemberRepresentation member : orgs.members(realm, org.getId())) {
            if (Boolean.TRUE.equals(member.isEnabled())) {
                users.setEnabled(realm, member.getId(), false);
                // the refresh tokens stop working; an access token already issued lives until it expires
                users.logout(realm, member.getId());
                disabled.add(member.getId());
            }
        }
        attributes(org).put(DISABLED_MEMBERS_ATTRIBUTE, disabled);
    }

    private void enableMembers(String realm, OrganizationRepresentation org) {
        List<String> disabled = attributes(org).getOrDefault(DISABLED_MEMBERS_ATTRIBUTE, List.of());
        for (String userId : disabled) {
            // a user deleted in the meantime is skipped
            if (users.find(realm, userId).isPresent()) {
                users.setEnabled(realm, userId, true);
            }
        }
        attributes(org).remove(DISABLED_MEMBERS_ATTRIBUTE);
    }

    private static Map<String, List<String>> attributes(OrganizationRepresentation org) {
        if (org.getAttributes() == null) {
            org.setAttributes(new HashMap<>());
        }
        return org.getAttributes();
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

        // the org was just created: the owner cannot be a member yet, the result can be ignored
        orgs.addMember(realm, orgId, ownerId);
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
