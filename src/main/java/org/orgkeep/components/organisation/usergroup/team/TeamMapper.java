package org.orgkeep.components.organisation.usergroup.team;

import org.orgkeep.api.model.CatalogRoles;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.TeamCatalogRole;
import org.orgkeep.api.model.TeamGrants;
import org.orgkeep.api.model.TeamProduct;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.orgkeep.api.model.CatalogRoles.firstValue;

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

    /** The features asked for by the org admin, sorted. null: every feature the org bought. */
    default List<String> askedFeatures(Map<String, List<String>> attributes, String catalog, String product) {
        if (!TeamGrants.LISTED_FEATURES.equals(firstValue(attributes, TeamGrants.product(catalog, product)))) {
            return null;
        }
        return attributes.getOrDefault(TeamGrants.features(catalog, product), List.of()).stream()
                .distinct()
                .sorted()
                .toList();
    }

    /** The features the members get: those asked for (all if none listed) that the org bought, sorted. */
    default List<String> givenFeatures(Map<String, List<String>> attributes, String catalog, String product,
                                       List<String> boughtFeatures) {
        List<String> asked = askedFeatures(attributes, catalog, product);
        return boughtFeatures.stream()
                .filter(feature -> asked == null || asked.contains(feature))
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

    /**
     * The quotas of the team by metered feature of the product, sorted by feature. The cycle is null if it was removed
     * outside of Orgkeep (the cycle of the org then applies).
     */
    default Map<String, FeatureQuota> featureQuotas(Map<String, List<String>> attributes, String catalog, String product) {
        Map<String, FeatureQuota> quotas = new TreeMap<>();
        attributes.keySet().forEach(attribute -> {
            String feature = TeamGrants.featureOfQuota(attribute, catalog, product);
            if (feature == null) {
                return;
            }
            try {
                long quota = Long.parseLong(firstValue(attributes, attribute));
                String cycle = firstValue(attributes, TeamGrants.featureQuotaCycle(catalog, product, feature));
                quotas.put(feature, new FeatureQuota(quota, cycle == null ? null : QuotaCycle.fromValue(cycle).orElse(null)));
            } catch (NumberFormatException e) {
                // changed outside of Orgkeep: ignored, like a quota of product that is not a number
            }
        });
        return quotas;
    }

    /**
     * The attributes of the team with new choices for the product. The derived attributes are left to TeamAlignment.
     *
     * @param features      null: every feature the org bought
     * @param quota         null: no quota of team (then no cycle either)
     * @param featureQuotas metered product: the quotas of the team by feature, cycles included. Replace the previous ones
     */
    default Map<String, List<String>> withChoices(Map<String, List<String>> attributes, String catalog, String product,
                                                  List<String> features, Long quota, QuotaCycle cycle,
                                                  Map<String, FeatureQuota> featureQuotas) {
        Map<String, List<String>> updated = new HashMap<>(attributes);
        updated.put(TeamGrants.product(catalog, product),
                List.of(features == null ? TeamGrants.ALL_FEATURES : TeamGrants.LISTED_FEATURES));
        putOrRemove(updated, TeamGrants.features(catalog, product),
                features == null ? List.of() : features.stream().distinct().sorted().toList());
        putOrRemove(updated, TeamGrants.quota(catalog, product),
                quota == null ? List.of() : List.of(quota.toString()));
        putOrRemove(updated, TeamGrants.quotaCycle(catalog, product),
                quota == null || cycle == null ? List.of() : List.of(cycle.getValue()));
        featureQuotas(attributes, catalog, product).keySet()
                .forEach(feature -> removeFeatureQuota(updated, catalog, product, feature));
        featureQuotas.forEach((feature, featureQuota) -> {
            updated.put(TeamGrants.featureQuota(catalog, product, feature), List.of(featureQuota.quota().toString()));
            updated.put(TeamGrants.featureQuotaCycle(catalog, product, feature), List.of(featureQuota.cycle().getValue()));
        });
        return updated;
    }

    /** The attributes of the team without anything about the product: choices and derived values. */
    default Map<String, List<String>> withoutProduct(Map<String, List<String>> attributes, String catalog, String product) {
        Map<String, List<String>> updated = new HashMap<>(attributes);
        featureQuotas(attributes, catalog, product).keySet()
                .forEach(feature -> removeFeatureQuota(updated, catalog, product, feature));
        updated.remove(TeamGrants.product(catalog, product));
        updated.remove(TeamGrants.features(catalog, product));
        updated.remove(TeamGrants.quota(catalog, product));
        updated.remove(TeamGrants.quotaCycle(catalog, product));
        updated.keySet().removeIf(attribute -> CatalogRoles.isClaimOf(attribute, catalog, product));
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
            products.add(new TeamProduct(catalog, product, askedFeatures(attributes, catalog, product), effective,
                    quota, quota == null ? null : quotaCycle(attributes, catalog, product),
                    featureQuotas(attributes, catalog, product)));
        });
        products.sort(Comparator.comparing(TeamProduct::catalog).thenComparing(TeamProduct::product));
        return products;
    }

    /**
     * The catalog roles given by a team, sorted by catalog then role: the roles of the catalog clients that are neither
     * a product it gives nor a feature of one ("shop-repository:search").
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

    /** The quota of the team for a metered feature, and its cycle. */
    static void removeFeatureQuota(Map<String, List<String>> attributes, String catalog, String product, String feature) {
        attributes.remove(TeamGrants.featureQuota(catalog, product, feature));
        attributes.remove(TeamGrants.featureQuotaCycle(catalog, product, feature));
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
