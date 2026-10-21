package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.TeamCatalogRole;
import io.orgkeep.api.model.TeamGrants;
import io.orgkeep.api.model.TeamProduct;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.orgkeep.api.model.CatalogRoles.firstValue;

/**
 * Between what a team gives (API records) and the attributes and role mappings of its group (see {@link TeamGrants}).
 * Only default methods, like ProductMapper: everything is read from attributes by hand.
 */
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface TeamMapper {

    /** True if the team gives the product (choice of an org admin). */
    default boolean gives(Map<String, List<String>> attributes, String catalog, String product) {
        return attributes.containsKey(TeamGrants.product(catalog, product));
    }

    /** The roles asked for by the org admin, sorted. null: every role the org bought. */
    default List<String> askedRoles(Map<String, List<String>> attributes, String catalog, String product) {
        if (!TeamGrants.LISTED_ROLES.equals(firstValue(attributes, TeamGrants.product(catalog, product)))) {
            return null;
        }
        return attributes.getOrDefault(TeamGrants.roles(catalog, product), List.of()).stream()
                .distinct()
                .sorted()
                .toList();
    }

    /** The roles the members get: those asked for (all if none listed) that the org bought, sorted. */
    default List<String> givenRoles(Map<String, List<String>> attributes, String catalog, String product,
                                    List<String> boughtRoles) {
        List<String> asked = askedRoles(attributes, catalog, product);
        return boughtRoles.stream()
                .filter(role -> asked == null || asked.contains(role))
                .distinct()
                .sorted()
                .toList();
    }

    default Long quota(Map<String, List<String>> attributes, String catalog, String product) {
        String quota = firstValue(attributes, TeamGrants.quota(catalog, product));
        try {
            return quota == null ? null : Long.valueOf(quota);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    default QuotaCycle quotaCycle(Map<String, List<String>> attributes, String catalog, String product) {
        String cycle = firstValue(attributes, TeamGrants.quotaCycle(catalog, product));
        return cycle == null ? null : QuotaCycle.fromValue(cycle).orElse(null);
    }

    /** The products for which the team has a quota, as their {@link TeamGrants#product} attribute. */
    default Set<String> productsWithQuota(Map<String, List<String>> attributes) {
        Set<String> products = new HashSet<>();
        attributes.keySet().forEach(attribute -> {
            String[] grant = TeamGrants.productOf(attribute);
            if (grant != null && quota(attributes, grant[0], grant[1]) != null) {
                products.add(attribute);
            }
        });
        return products;
    }

    /**
     * The attributes of the team with new choices for the product. The derived attributes are left to TeamAlignment.
     *
     * @param roles null: every role the org bought
     * @param quota null: no quota of team (then no cycle either)
     */
    default Map<String, List<String>> withChoices(Map<String, List<String>> attributes, String catalog, String product,
                                                  List<String> roles, Long quota, QuotaCycle cycle) {
        Map<String, List<String>> updated = new HashMap<>(attributes);
        updated.put(TeamGrants.product(catalog, product),
                List.of(roles == null ? TeamGrants.ALL_ROLES : TeamGrants.LISTED_ROLES));
        putOrRemove(updated, TeamGrants.roles(catalog, product),
                roles == null ? List.of() : roles.stream().distinct().sorted().toList());
        putOrRemove(updated, TeamGrants.quota(catalog, product),
                quota == null ? List.of() : List.of(quota.toString()));
        putOrRemove(updated, TeamGrants.quotaCycle(catalog, product),
                quota == null || cycle == null ? List.of() : List.of(cycle.getValue()));
        return updated;
    }

    /** The attributes of the team without anything about the product: choices and derived values. */
    default Map<String, List<String>> withoutProduct(Map<String, List<String>> attributes, String catalog, String product) {
        Map<String, List<String>> updated = new HashMap<>(attributes);
        updated.remove(TeamGrants.product(catalog, product));
        updated.remove(TeamGrants.roles(catalog, product));
        updated.remove(TeamGrants.quota(catalog, product));
        updated.remove(TeamGrants.quotaCycle(catalog, product));
        updated.remove(CatalogRoles.quotasAttribute(catalog, product));
        updated.remove(CatalogRoles.paramsAttribute(catalog, product));
        return updated;
    }

    /**
     * The products given by a team, sorted by catalog then product.
     *
     * @param clientRoles the client roles granted to its group, by clientId: the roles the members really get
     */
    default List<TeamProduct> toTeamProducts(Map<String, List<String>> attributes, Map<String, Set<String>> clientRoles) {
        List<TeamProduct> products = new ArrayList<>();
        attributes.keySet().forEach(attribute -> {
            String[] grant = TeamGrants.productOf(attribute);
            if (grant == null) {
                return;
            }
            String catalog = grant[0];
            String product = grant[1];
            String prefix = product + CatalogRoles.SEPARATOR;
            List<String> effective = clientRoles.getOrDefault(catalog, Set.of()).stream()
                    .filter(role -> role.startsWith(prefix))
                    .map(role -> role.substring(prefix.length()))
                    .sorted()
                    .toList();
            Long quota = quota(attributes, catalog, product);
            products.add(new TeamProduct(catalog, product, askedRoles(attributes, catalog, product), effective,
                    quota, quota == null ? null : quotaCycle(attributes, catalog, product)));
        });
        products.sort(Comparator.comparing(TeamProduct::catalog).thenComparing(TeamProduct::product));
        return products;
    }

    /**
     * The catalog roles given by a team, sorted by catalog then role: the roles of the catalog clients that are neither
     * a product it gives nor a role of one ("shop-repository:scan").
     *
     * @param clientRoles the client roles granted to its group, by clientId
     * @param catalogs    the names of the catalogs of the realm
     */
    default List<TeamCatalogRole> toTeamCatalogRoles(Map<String, List<String>> attributes,
                                                     Map<String, Set<String>> clientRoles, Set<String> catalogs) {
        List<TeamCatalogRole> roles = new ArrayList<>();
        clientRoles.forEach((clientId, names) -> {
            if (!catalogs.contains(clientId)) {
                return;
            }
            names.stream()
                    .filter(name -> !name.contains(CatalogRoles.SEPARATOR) && !gives(attributes, clientId, name))
                    .forEach(name -> roles.add(new TeamCatalogRole(clientId, name)));
        });
        roles.sort(Comparator.comparing(TeamCatalogRole::catalog).thenComparing(TeamCatalogRole::role));
        return roles;
    }

    /** Keycloak keeps an attribute with no value: an empty list removes it. */
    static void putOrRemove(Map<String, List<String>> attributes, String name, List<String> values) {
        if (values.isEmpty()) {
            attributes.remove(name);
        } else {
            attributes.put(name, values);
        }
    }
}
