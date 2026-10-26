package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.FeatureQuota;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.TeamGrants;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.components.catalog.product.ProductMapper;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes on the groups of the teams what their choices give, given what the org bought (the native engine: nothing is
 * computed when a token is issued). For a team giving a product P of the catalog C:
 * <ul>
 *   <li>role mappings of the catalog client: P (simple product only: a metered product has no access role), and P:f
 *       for each feature f asked for (all if none listed) that the org bought;</li>
 *   <li>{@code C.P.quotas}: simple product, the pool of the org, plus the pool of the team if it has a quota; metered
 *       product, the pool of the org for each metered feature given, plus the pool of the team if it has a quota for
 *       this feature;</li>
 *   <li>{@code C.P.params}: the parameter values of the org.</li>
 * </ul>
 * Called directly, not through the event bus, by the services that change a team or an assignment: the next token is
 * right as soon as the call answers, and a failure reaches the caller.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class TeamAlignment {
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final CatalogSetup catalogSetup;
    private final ProductMapper productMapper;
    private final TeamMapper teamMapper;

    /**
     * Realigns every team of the org giving the product with its assignment (quota, bought features, parameters).
     * A product no longer assigned is taken away from them.
     */
    public void align(String realm, String orgName, String catalog, String product) {
        List<GroupRepresentation> teams = teams(realm, orgName).stream()
                .filter(team -> teamMapper.gives(attributes(team), catalog, product))
                .toList();
        if (teams.isEmpty()) {
            return;
        }
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        Optional<GroupRepresentation> assignment = groups.find(realm, OrgGroups.assignment(orgName, catalog, product));
        if (assignment.isEmpty()) {
            teams.forEach(team -> clear(realm, ids.clientUuid(), team, catalog, product));
            return;
        }

        OrgProduct orgProduct = productMapper.toOrgProduct(catalog, assignment.get());
        // one call for every role of the catalog: the representations to grant, and the parameter schema
        List<RoleRepresentation> catalogRoles = clients.listRoles(realm, ids.clientUuid());
        List<ParamDefinition> schema = catalogRoles.stream()
                .filter(role -> product.equals(role.getName()) && productMapper.isProduct(role))
                .findFirst()
                .map(role -> productMapper.params(role.getAttributes()))
                .orElse(List.of());
        boolean metered = productMapper.isMetered(catalogRoles, product);
        teams.forEach(team -> apply(realm, orgName, ids.clientUuid(), catalogRoles, metered, schema, orgProduct, team));
        log.info("{} team(s) of organisation '{}' realigned on product '{}' of catalog '{}' in realm '{}'",
                teams.size(), orgName, product, catalog, realm);
    }

    /** Every team of the org loses the product: role mappings, choices and derived attributes. */
    public void revoke(String realm, String orgName, String catalog, String product) {
        List<GroupRepresentation> teams = teams(realm, orgName).stream()
                .filter(team -> teamMapper.gives(attributes(team), catalog, product)
                        || attributes(team).containsKey(CatalogRoles.quotasAttribute(catalog, product)))
                .toList();
        if (teams.isEmpty()) {
            return;
        }
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        teams.forEach(team -> clear(realm, clientUuid, team, catalog, product));
    }

    /** The team loses the product: role mappings, choices and derived attributes. */
    public void clear(String realm, String clientUuid, GroupRepresentation team, String catalog, String product) {
        clients.revokeFromGroup(realm, clientUuid, team.getId(), clients.rolesOfGroup(realm, clientUuid, team.getId()).stream()
                .filter(role -> isOfProduct(role.getName(), product))
                .toList());
        Map<String, List<String>> attributes = attributes(team);
        Map<String, List<String>> updated = teamMapper.withoutProduct(attributes, catalog, product);
        if (!updated.equals(attributes)) {
            groups.replaceAttributes(realm, team.getId(), updated);
        }
    }

    /** Every team of the org loses the catalog roles of the catalog. */
    public void revokeCatalogRoles(String realm, String orgName, String catalog) {
        List<GroupRepresentation> teams = teams(realm, orgName);
        if (teams.isEmpty()) {
            return;
        }
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        Set<String> catalogRoles = new HashSet<>();
        clients.listRoles(realm, clientUuid).stream()
                .filter(role -> CatalogRoles.TYPE_CATALOG_ROLE.equals(CatalogRoles.typeOf(role.getAttributes())))
                .forEach(role -> catalogRoles.add(role.getName()));
        teams.forEach(team -> clients.revokeFromGroup(realm, clientUuid, team.getId(),
                clients.rolesOfGroup(realm, clientUuid, team.getId()).stream()
                        .filter(role -> catalogRoles.contains(role.getName()))
                        .toList()));
        log.info("Catalog roles of catalog '{}' revoked from the teams of organisation '{}' in realm '{}'",
                catalog, orgName, realm);
    }

    /** The teams of every org lose the catalog role. The users holding it directly keep it. */
    public void revokeCatalogRoleFromTeams(String realm, String catalog, String role) {
        String clientUuid = catalogSetup.get(realm, catalog).clientUuid();
        List<GroupRepresentation> teams = clients.listGroupsWithRole(realm, clientUuid, role).stream()
                .filter(group -> OrgGroups.isUserGroup(group.getPath()))
                .toList();
        teams.forEach(team -> clients.revokeFromGroup(realm, clientUuid, team.getId(), role));
        log.info("Catalog role '{}' of catalog '{}' revoked from {} team(s) in realm '{}'", role, catalog, teams.size(), realm);
    }

    /** The teams of the org that listed a deleted feature of a product, or had a quota for it, forget it. */
    public void forgetFeature(String realm, String orgName, String catalog, String product, String feature) {
        String featuresAttribute = TeamGrants.features(catalog, product);
        for (GroupRepresentation team : teams(realm, orgName)) {
            Map<String, List<String>> attributes = attributes(team);
            Map<String, List<String>> updated = new HashMap<>(attributes);
            List<String> features = new ArrayList<>(attributes.getOrDefault(featuresAttribute, List.of()));
            if (features.remove(feature)) {
                TeamMapper.putOrRemove(updated, featuresAttribute, features);
            }
            TeamMapper.removeFeatureQuota(updated, catalog, product, feature);
            if (!updated.equals(attributes)) {
                groups.replaceAttributes(realm, team.getId(), updated);
            }
        }
    }

    /** True if the org has at least one product assigned in the catalog. */
    public boolean hasAssignment(String realm, String orgName, String catalog) {
        return groups.findId(realm, OrgGroups.catalog(orgName, catalog))
                .map(catalogGroupId -> !groups.children(realm, catalogGroupId).isEmpty())
                .orElse(false);
    }

    /** The groups /{org}/user-groups/*, attributes included. None for an org created outside of Orgkeep. */
    public List<GroupRepresentation> teams(String realm, String orgName) {
        return groups.findId(realm, OrgGroups.userGroups(orgName))
                .map(userGroupsId -> groups.children(realm, userGroupsId))
                .orElse(List.of());
    }

    private void apply(String realm, String orgName, String clientUuid, List<RoleRepresentation> catalogRoles,
                       boolean metered, List<ParamDefinition> schema, OrgProduct orgProduct, GroupRepresentation team) {
        String catalog = orgProduct.catalog();
        String product = orgProduct.product();
        Map<String, List<String>> attributes = attributes(team);
        List<String> given = teamMapper.givenFeatures(attributes, catalog, product, orgProduct.boughtFeatures());

        // role mappings: only those of this product are touched. A metered product has no access role: the API checks
        // the feature a call needs
        Set<String> wanted = new HashSet<>();
        if (!metered) {
            wanted.add(product);
        }
        given.forEach(feature -> wanted.add(CatalogRoles.feature(product, feature)));
        List<RoleRepresentation> current = clients.rolesOfGroup(realm, clientUuid, team.getId()).stream()
                .filter(role -> isOfProduct(role.getName(), product))
                .toList();
        Set<String> currentNames = new HashSet<>();
        current.forEach(role -> currentNames.add(role.getName()));
        clients.revokeFromGroup(realm, clientUuid, team.getId(), current.stream()
                .filter(role -> !wanted.contains(role.getName()))
                .toList());
        clients.grantToGroup(realm, clientUuid, team.getId(), catalogRoles.stream()
                .filter(role -> wanted.contains(role.getName()) && !currentNames.contains(role.getName()))
                .toList());

        // attributes read by the mappers of the product
        List<String> pools = metered ? featurePools(orgName, orgProduct, team, given) : productPools(orgName, orgProduct, team);
        // a metered product whose features given are no longer bought gives nothing: no parameters either. The choices
        // stay, they take effect again if the org buys the features again
        String params = metered && given.isEmpty() ? null : TeamClaims.params(schema, orgProduct.params());

        Map<String, List<String>> updated = new HashMap<>(attributes);
        TeamMapper.putOrRemove(updated, CatalogRoles.quotasAttribute(catalog, product), pools);
        TeamMapper.putOrRemove(updated, CatalogRoles.paramsAttribute(catalog, product),
                params == null ? List.of() : List.of(params));
        if (!updated.equals(attributes)) {
            groups.replaceAttributes(realm, team.getId(), updated);
        }
    }

    /** Simple product: the pool of the org, plus the pool of the team if it has a quota. */
    private List<String> productPools(String orgName, OrgProduct orgProduct, GroupRepresentation team) {
        String catalog = orgProduct.catalog();
        String product = orgProduct.product();
        Map<String, List<String>> attributes = attributes(team);
        List<String> pools = new ArrayList<>();
        if (orgProduct.quota() != null && orgProduct.cycle() != null) {
            pools.add(TeamClaims.pool(TeamGrants.orgPool(orgName), orgProduct.quota(), orgProduct.cycle()));
        }
        Long teamQuota = teamMapper.quota(attributes, catalog, product);
        if (teamQuota != null) {
            // a quota of team without cycle (changed outside of Orgkeep) takes the cycle of the org
            var cycle = Optional.ofNullable(teamMapper.quotaCycle(attributes, catalog, product)).orElse(orgProduct.cycle());
            if (cycle != null) {
                pools.add(TeamClaims.pool(TeamGrants.teamPool(orgName, team.getName()), teamQuota, cycle));
            }
        }
        return pools;
    }

    /**
     * Metered product: for each metered feature the team gives, the pool of the org, plus the pool of the team if it
     * has a quota for this feature.
     */
    private List<String> featurePools(String orgName, OrgProduct orgProduct, GroupRepresentation team, List<String> given) {
        Map<String, FeatureQuota> teamQuotas = teamMapper.featureQuotas(attributes(team), orgProduct.catalog(), orgProduct.product());
        List<String> pools = new ArrayList<>();
        given.forEach(feature -> {
            // the org quota only exists for a metered feature: a feature that is not metered has no pool at all
            FeatureQuota orgQuota = orgProduct.featureQuotas().get(feature);
            if (orgQuota == null) {
                return;
            }
            pools.add(TeamClaims.pool(feature, TeamGrants.orgPool(orgName), orgQuota.quota(), orgQuota.cycle()));
            FeatureQuota teamQuota = teamQuotas.get(feature);
            if (teamQuota != null) {
                // a quota of team without cycle (changed outside of Orgkeep) takes the cycle of the org
                var cycle = Optional.ofNullable(teamQuota.cycle()).orElse(orgQuota.cycle());
                pools.add(TeamClaims.pool(feature, TeamGrants.teamPool(orgName, team.getName()), teamQuota.quota(), cycle));
            }
        });
        return pools;
    }

    /** The product itself, or one of its features ("shop-repository:scan"). */
    private static boolean isOfProduct(String roleName, String product) {
        return roleName.equals(product) || roleName.startsWith(product + CatalogRoles.SEPARATOR);
    }

    private static Map<String, List<String>> attributes(GroupRepresentation group) {
        return group.getAttributes() == null ? Map.of() : group.getAttributes();
    }
}
