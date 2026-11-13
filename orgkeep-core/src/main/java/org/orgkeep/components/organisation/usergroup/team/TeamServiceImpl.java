package org.orgkeep.components.organisation.usergroup.team;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.exceptions.CatalogNotAssignedException;
import org.orgkeep.api.exceptions.CatalogRoleNotFoundException;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.ProductNotAssignedException;
import org.orgkeep.api.exceptions.UserGroupNotFoundException;
import org.orgkeep.api.model.AvailableFeature;
import org.orgkeep.api.model.AvailableProduct;
import org.orgkeep.api.model.CatalogRole;
import org.orgkeep.api.model.CatalogRoles;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.TeamProduct;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.components.catalog.product.ProductMapper;
import org.orgkeep.components.catalog.role.CatalogRoleMapper;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.orgkeep.api.model.CatalogRoles.firstValue;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {
    private final KeycloakOrgs orgs;
    private final KeycloakGroups groups;
    private final KeycloakClients clients;
    private final CatalogSetup catalogSetup;
    private final ProductService productService;
    private final CatalogRoleService catalogRoleService;
    private final TeamAlignment alignment;
    private final ProductMapper productMapper;
    private final CatalogRoleMapper catalogRoleMapper;
    private final TeamMapper teamMapper;
    private final ResourceLocks locks;

    /** The assignments of the org, then one call per catalog for the display names of its products and roles. */
    @Override
    public List<AvailableProduct> availableProducts(String realm, String orgName) {
        List<OrgProduct> assigned = productService.listAssigned(realm, orgName);
        Map<String, Map<String, RoleRepresentation>> rolesByCatalog = new HashMap<>();
        List<AvailableProduct> available = new ArrayList<>();
        for (OrgProduct orgProduct : assigned) {
            Map<String, RoleRepresentation> roles = rolesByCatalog.computeIfAbsent(orgProduct.catalog(),
                    catalog -> clients.listRoles(realm, catalogSetup.get(realm, catalog).clientUuid()).stream()
                            .collect(Collectors.toMap(RoleRepresentation::getName, Function.identity())));
            RoleRepresentation product = roles.get(orgProduct.product());
            List<AvailableFeature> boughtFeatures = orgProduct.boughtFeatures().stream()
                    .map(name -> {
                        RoleRepresentation role = roles.get(CatalogRoles.feature(orgProduct.product(), name));
                        // a bought feature without its role was changed outside of Orgkeep: shown by its name only
                        Feature feature = role == null ? new Feature(name, null) : productMapper.toFeature(orgProduct.product(), role);
                        FeatureQuota quota = orgProduct.featureQuotas().get(name);
                        return new AvailableFeature(feature.name(), feature.displayName(), feature.metered(),
                                quota == null ? null : quota.quota(), quota == null ? null : quota.cycle());
                    })
                    .toList();
            boolean metered = productMapper.isMetered(List.copyOf(roles.values()), orgProduct.product());
            available.add(new AvailableProduct(orgProduct.catalog(), orgProduct.product(), displayName(product),
                    metered, orgProduct.quota(), orgProduct.cycle(), boughtFeatures));
        }
        return available;
    }

    @Override
    public List<String> catalogs(String realm, String orgName) {
        return productService.listAssigned(realm, orgName).stream()
                .map(OrgProduct::catalog)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public List<CatalogRole> grantableRoles(String realm, String orgName, String catalog) {
        orgs.getByName(realm, orgName);
        if (!alignment.hasAssignment(realm, orgName, catalog)) {
            throw new CatalogNotAssignedException(orgName, catalog);
        }
        return catalogRoleService.list(realm, catalog).stream()
                .filter(CatalogRole::orgGrantable)
                .toList();
    }

    @Override
    public TeamProduct grantProduct(String realm, String orgName, String team, String catalog, String product,
                                    GrantProductRequest request) {
        // a short write: ORG shared (refused while the org is being disabled or deleted, or a product renegotiated),
        // the team exclusively (its attributes are read then written as a whole)
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, team, LockMode.EXCLUSIVE))) {
            return grantProductLocked(realm, orgName, team, catalog, product, request);
        }
    }

    private TeamProduct grantProductLocked(String realm, String orgName, String team, String catalog, String product,
                                           GrantProductRequest request) {
        orgs.getByName(realm, orgName);
        String teamId = teamId(realm, orgName, team);
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        GroupRepresentation assignment = groups.find(realm, OrgGroups.assignment(orgName, catalog, product))
                .orElseThrow(() -> new ProductNotAssignedException(orgName, catalog, product));
        OrgProduct orgProduct = productMapper.toOrgProduct(catalog, assignment);

        boolean metered = productMapper.isMetered(clients.listRoles(realm, clientUuid), product);
        if (metered) {
            // a metered product has no quota of product: the quotas of team are by metered feature
            if (request.quota() != null || request.cycle() != null) {
                throw new InvalidRequestException(("Product '%s' has metered features: no quota of team for the product, "
                        + "only by metered feature (featureQuotas)").formatted(product));
            }
            // without an access role, a metered product without any feature would give nothing
            if (request.features() != null && request.features().isEmpty()) {
                throw new InvalidRequestException(
                        "Product '%s' has metered features: a team gives at least one of them".formatted(product));
            }
        } else if (!request.featureQuotas().isEmpty()) {
            throw new InvalidRequestException(
                    "Product '%s' has no metered feature: its quota of team is the quota of the product".formatted(product));
        }
        if (request.cycle() != null && request.quota() == null) {
            throw new InvalidRequestException("A cycle is only given with a quota of team");
        }

        // never more than the org bought
        if (request.features() != null) {
            List<String> notBought = request.features().stream()
                    .filter(feature -> !orgProduct.boughtFeatures().contains(feature))
                    .distinct()
                    .sorted()
                    .toList();
            if (!notBought.isEmpty()) {
                throw new InvalidRequestException("Features of the product '%s' the organisation '%s' did not buy: %s"
                        .formatted(product, orgName, notBought));
            }
        }
        List<String> asked = request.features() == null ? null : request.features().stream().distinct().sorted().toList();
        List<String> effective = orgProduct.boughtFeatures().stream()
                .filter(feature -> asked == null || asked.contains(feature))
                .sorted()
                .toList();
        // a quota of team only for a metered feature the team gives: the org has a quota for each one it bought
        List<String> withoutPool = request.featureQuotas().keySet().stream()
                .filter(feature -> !effective.contains(feature) || !orgProduct.featureQuotas().containsKey(feature))
                .sorted()
                .toList();
        if (!withoutPool.isEmpty()) {
            throw new InvalidRequestException("Quotas of team for features of the product '%s' that are not metered "
                    + "features given by the team: %s".formatted(product, withoutPool));
        }

        // the form proposes the cycle of the org: also the default of the API. Copied, it does not follow it afterwards
        QuotaCycle cycle = request.quota() == null ? null
                : request.cycle() != null ? request.cycle() : orgProduct.cycle();
        Map<String, FeatureQuota> featureQuotas = new TreeMap<>();
        request.featureQuotas().forEach((feature, quota) -> featureQuotas.put(feature, new FeatureQuota(quota.quota(),
                quota.cycle() != null ? quota.cycle() : orgProduct.featureQuotas().get(feature).cycle())));

        groups.replaceAttributes(realm, teamId, teamMapper.withChoices(groups.attributes(realm, teamId),
                catalog, product, request.features(), request.quota(), cycle, featureQuotas));
        alignment.align(realm, orgName, catalog, product);
        log.info("Product '{}' of catalog '{}' given by team '{}' of organisation '{}' in realm '{}'",
                product, catalog, team, orgName, realm);

        return new TeamProduct(catalog, product, asked, effective, request.quota(), cycle, featureQuotas);
    }

    @Override
    public void revokeProduct(String realm, String orgName, String team, String catalog, String product) {
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, team, LockMode.EXCLUSIVE))) {
            orgs.getByName(realm, orgName);
            if (!OrgGroups.isUserGroupName(team)) {
                throw new UserGroupNotFoundException(orgName, team);
            }
            // the full group, read under the lock: its attributes are needed
            GroupRepresentation group = groups.find(realm, OrgGroups.userGroup(orgName, team))
                    .orElseThrow(() -> new UserGroupNotFoundException(orgName, team));
            String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
            alignment.clear(realm, clientUuid, group, catalog, product);
            log.info("Product '{}' of catalog '{}' no longer given by team '{}' of organisation '{}' in realm '{}'",
                    product, catalog, team, orgName, realm);
        }
    }

    @Override
    public void grantCatalogRole(String realm, String orgName, String team, String catalog, String role) {
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, team, LockMode.EXCLUSIVE))) {
            grantCatalogRoleLocked(realm, orgName, team, catalog, role);
        }
    }

    private void grantCatalogRoleLocked(String realm, String orgName, String team, String catalog, String role) {
        orgs.getByName(realm, orgName);
        String teamId = teamId(realm, orgName, team);
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        RoleRepresentation catalogRole = getCatalogRole(realm, clientUuid, catalog, role);
        if (!catalogRoleMapper.toCatalogRole(catalog, catalogRole).orgGrantable()) {
            throw new OperationNotAllowedException(
                    "The catalog role '%s' is reserved to the realm owner".formatted(role));
        }
        if (!alignment.hasAssignment(realm, orgName, catalog)) {
            throw new OperationNotAllowedException(
                    "The organisation '%s' has no product of the catalog '%s'".formatted(orgName, catalog));
        }

        boolean granted = clients.rolesOfGroup(realm, clientUuid, teamId).stream()
                .anyMatch(r -> role.equals(r.getName()));
        if (!granted) {
            clients.grantToGroup(realm, clientUuid, teamId, List.of(catalogRole));
            log.info("Catalog role '{}' of catalog '{}' given by team '{}' of organisation '{}' in realm '{}'",
                    role, catalog, team, orgName, realm);
        }
    }

    @Override
    public void revokeCatalogRole(String realm, String orgName, String team, String catalog, String role) {
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.team(realm, orgName, team, LockMode.EXCLUSIVE))) {
            orgs.getByName(realm, orgName);
            String teamId = teamId(realm, orgName, team);
            String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
            // only a catalog role: a product is taken away with its quota and features (revokeProduct)
            getCatalogRole(realm, clientUuid, catalog, role);
            clients.revokeFromGroup(realm, clientUuid, teamId, role);
        }
    }

    private RoleRepresentation getCatalogRole(String realm, String clientUuid, String catalog, String role) {
        return clients.findRole(realm, clientUuid, role)
                .filter(catalogRoleMapper::isCatalogRole)
                .orElseThrow(() -> new CatalogRoleNotFoundException(catalog, role));
    }

    /** The org must exist, checked by the caller: an unknown org answers "unknown org", not "unknown group". */
    private String teamId(String realm, String orgName, String team) {
        if (!OrgGroups.isUserGroupName(team)) {
            throw new UserGroupNotFoundException(orgName, team);
        }
        return groups.findId(realm, OrgGroups.userGroup(orgName, team))
                .orElseThrow(() -> new UserGroupNotFoundException(orgName, team));
    }

    private static String displayName(RoleRepresentation role) {
        return role == null ? null : firstValue(role.getAttributes(), CatalogRoles.DISPLAY_NAME);
    }
}
