package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.CatalogRoleService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.TeamService;
import io.orgkeep.api.exceptions.CatalogNotAssignedException;
import io.orgkeep.api.exceptions.CatalogRoleNotFoundException;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.ProductNotAssignedException;
import io.orgkeep.api.exceptions.UserGroupNotFoundException;
import io.orgkeep.api.model.AvailableProduct;
import io.orgkeep.api.model.CatalogRole;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ProductRole;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.TeamProduct;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.components.catalog.product.ProductMapper;
import io.orgkeep.components.catalog.role.CatalogRoleMapper;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.orgkeep.api.model.CatalogRoles.firstValue;

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
    private final TeamQuotas teamQuotas;
    private final ProductMapper productMapper;
    private final CatalogRoleMapper catalogRoleMapper;
    private final TeamMapper teamMapper;

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
            List<ProductRole> boughtRoles = orgProduct.boughtRoles().stream()
                    .map(role -> new ProductRole(role, displayName(roles.get(CatalogRoles.productRole(orgProduct.product(), role)))))
                    .toList();
            available.add(new AvailableProduct(orgProduct.catalog(), orgProduct.product(), displayName(product),
                    orgProduct.quota(), orgProduct.cycle(), boughtRoles));
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
        orgs.getByName(realm, orgName);
        String teamId = teamId(realm, orgName, team);
        if (request.cycle() != null && request.quota() == null) {
            throw new InvalidRequestException("A cycle is only given with a quota of team");
        }
        catalogSetup.get(realm, catalog);
        GroupRepresentation assignment = groups.find(realm, OrgGroups.assignment(orgName, catalog, product))
                .orElseThrow(() -> new ProductNotAssignedException(orgName, catalog, product));
        OrgProduct orgProduct = productMapper.toOrgProduct(catalog, assignment);

        // never more than the org bought
        if (request.roles() != null) {
            List<String> notBought = request.roles().stream()
                    .filter(role -> !orgProduct.boughtRoles().contains(role))
                    .distinct()
                    .sorted()
                    .toList();
            if (!notBought.isEmpty()) {
                throw new InvalidRequestException("Roles of the product '%s' the organisation '%s' did not buy: %s"
                        .formatted(product, orgName, notBought));
            }
        }
        if (request.quota() != null) {
            teamQuotas.checkQuota(realm, orgName, teamId, catalog, product);
        }
        // the form proposes the cycle of the org: also the default of the API. Copied, it does not follow it afterwards
        QuotaCycle cycle = request.quota() == null ? null
                : request.cycle() != null ? request.cycle() : orgProduct.cycle();

        groups.replaceAttributes(realm, teamId, teamMapper.withChoices(groups.attributes(realm, teamId),
                catalog, product, request.roles(), request.quota(), cycle));
        alignment.align(realm, orgName, catalog, product);
        log.info("Product '{}' of catalog '{}' given by team '{}' of organisation '{}' in realm '{}'",
                product, catalog, team, orgName, realm);

        List<String> asked = request.roles() == null ? null : request.roles().stream().distinct().sorted().toList();
        List<String> effective = orgProduct.boughtRoles().stream()
                .filter(role -> asked == null || asked.contains(role))
                .sorted()
                .toList();
        return new TeamProduct(catalog, product, asked, effective, request.quota(), cycle);
    }

    @Override
    public void revokeProduct(String realm, String orgName, String team, String catalog, String product) {
        orgs.getByName(realm, orgName);
        if (!OrgGroups.isUserGroupName(team)) {
            throw new UserGroupNotFoundException(orgName, team);
        }
        // the full group: its attributes are needed
        GroupRepresentation group = groups.find(realm, OrgGroups.userGroup(orgName, team))
                .orElseThrow(() -> new UserGroupNotFoundException(orgName, team));
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        alignment.clear(realm, clientUuid, group, catalog, product);
        log.info("Product '{}' of catalog '{}' no longer given by team '{}' of organisation '{}' in realm '{}'",
                product, catalog, team, orgName, realm);
    }

    @Override
    public void grantCatalogRole(String realm, String orgName, String team, String catalog, String role) {
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
        orgs.getByName(realm, orgName);
        String teamId = teamId(realm, orgName, team);
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        // only a catalog role: a product is taken away with its quota and roles (revokeProduct)
        getCatalogRole(realm, clientUuid, catalog, role);
        clients.revokeFromGroup(realm, clientUuid, teamId, role);
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
