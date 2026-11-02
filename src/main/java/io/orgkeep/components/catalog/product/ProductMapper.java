package io.orgkeep.components.catalog.product;

import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.FeatureQuota;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.QuotaCycle;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.orgkeep.api.model.CatalogRoles.firstValue;

/**
 * Between the products of the API and the Keycloak roles, groups and mappers that store them (see {@link CatalogRoles}).
 * Only default methods: the representations are built by hand from attributes, MapStruct has nothing to generate.
 * Kept as a @Mapper for consistency with the other packages (a CDI bean injected the same way).
 */
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface ProductMapper {

    /** The role of a product: its name, description, display name and parameter schema. */
    default RoleRepresentation toRoleRepresentation(String name, String displayName, String description,
                                                    List<ParamDefinition> params) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put(CatalogRoles.TYPE, List.of(CatalogRoles.TYPE_PRODUCT));
        attributes.put(CatalogRoles.DISPLAY_NAME, List.of(displayName));
        for (ParamDefinition param : params) {
            attributes.put(CatalogRoles.paramAttribute(param.name(), CatalogRoles.PARAM_TYPE), List.of(param.type().getValue()));
            attributes.put(CatalogRoles.paramAttribute(param.name(), CatalogRoles.PARAM_REQUIRED), List.of(String.valueOf(param.required())));
            if (param.defaultValue() != null) {
                attributes.put(CatalogRoles.paramAttribute(param.name(), CatalogRoles.PARAM_DEFAULT), List.of(param.defaultValue()));
            }
            if (!param.values().isEmpty()) {
                attributes.put(CatalogRoles.paramAttribute(param.name(), CatalogRoles.PARAM_VALUES), param.values());
            }
        }
        RoleRepresentation role = new RoleRepresentation(name, description, false);
        role.setAttributes(attributes);
        return role;
    }

    /** The client role of a feature of a product, e.g. "shop-repository:search". */
    default RoleRepresentation toRoleRepresentation(String product, Feature feature) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put(CatalogRoles.TYPE, List.of(CatalogRoles.TYPE_FEATURE));
        attributes.put(CatalogRoles.PRODUCT, List.of(product));
        attributes.put(CatalogRoles.METERED, List.of(String.valueOf(feature.metered())));
        if (feature.displayName() != null) {
            attributes.put(CatalogRoles.DISPLAY_NAME, List.of(feature.displayName()));
        }
        RoleRepresentation role = new RoleRepresentation(CatalogRoles.feature(product, feature.name()), null, false);
        role.setAttributes(attributes);
        return role;
    }

    /**
     * @param roles    every role of the catalog client, full representations: the features of this product are picked out
     * @param orgCount number of orgs the product is assigned to
     */
    default Product toProduct(String catalog, RoleRepresentation product, List<RoleRepresentation> roles, int orgCount) {
        List<Feature> features = roles.stream()
                .filter(role -> isFeatureOf(role, product.getName()))
                .map(role -> toFeature(product.getName(), role))
                .sorted(Comparator.comparing(Feature::name))
                .toList();
        return new Product(catalog, product.getName(), firstValue(product.getAttributes(), CatalogRoles.DISPLAY_NAME),
                blankToNull(product.getDescription()), features, features.stream().anyMatch(Feature::metered),
                params(product.getAttributes()), orgCount);
    }

    /**
     * True if the product has at least one metered feature: its kind, never stored.
     *
     * @param roles every role of the catalog client, full representations
     */
    default boolean isMetered(List<RoleRepresentation> roles, String product) {
        return roles.stream()
                .filter(role -> isFeatureOf(role, product))
                .anyMatch(role -> toFeature(product, role).metered());
    }

    /** The feature of a client role of the product ("shop-repository:search" gives "search"). */
    default Feature toFeature(String product, RoleRepresentation role) {
        return new Feature(
                role.getName().substring(product.length() + CatalogRoles.SEPARATOR.length()),
                firstValue(role.getAttributes(), CatalogRoles.DISPLAY_NAME),
                Boolean.parseBoolean(firstValue(role.getAttributes(), CatalogRoles.METERED)));
    }

    default boolean isProduct(RoleRepresentation role) {
        return CatalogRoles.TYPE_PRODUCT.equals(CatalogRoles.typeOf(role.getAttributes()));
    }

    default boolean isFeatureOf(RoleRepresentation role, String product) {
        return CatalogRoles.TYPE_FEATURE.equals(CatalogRoles.typeOf(role.getAttributes()))
                && product.equals(firstValue(role.getAttributes(), CatalogRoles.PRODUCT));
    }

    /** The parameter schema stored in the attributes of a product, sorted by name. */
    default List<ParamDefinition> params(Map<String, List<String>> attributes) {
        if (attributes == null) {
            return List.of();
        }
        List<ParamDefinition> params = new ArrayList<>();
        for (String attribute : attributes.keySet()) {
            String name = CatalogRoles.paramOfDefinition(attribute);
            if (name == null) {
                continue;
            }
            ParamType type = ParamType.fromValue(firstValue(attributes, attribute)).orElse(ParamType.STRING);
            params.add(new ParamDefinition(name, type,
                    Boolean.parseBoolean(firstValue(attributes, CatalogRoles.paramAttribute(name, CatalogRoles.PARAM_REQUIRED))),
                    firstValue(attributes, CatalogRoles.paramAttribute(name, CatalogRoles.PARAM_DEFAULT)),
                    attributes.getOrDefault(CatalogRoles.paramAttribute(name, CatalogRoles.PARAM_VALUES), List.of())));
        }
        params.sort(Comparator.comparing(ParamDefinition::name));
        return params;
    }

    /**
     * The two native mappers of a product on the scope of its catalog. They read the attributes of the groups of the user
     * (aggregate.attrs), set on the teams giving the product:
     * <ul>
     *   <li>quotas: multivalued, one JSON object per pool. Keycloak keeps a value given by several teams once. Empty for
     *       a metered product, whose quotas are those of its features ({@link #toFeatureMapper});</li>
     *   <li>params: a single JSON object.</li>
     * </ul>
     * Named after their attribute: unique in the scope, and found back when the product is deleted.
     */
    default List<ProtocolMapperRepresentation> toMappers(String catalog, String product) {
        return List.of(
                attributeMapper(CatalogRoles.quotasAttribute(catalog, product), true),
                attributeMapper(CatalogRoles.paramsAttribute(catalog, product), false));
    }

    /**
     * The native mapper of a metered feature: its quota pools, one JSON object per pool, in a claim of its own next to
     * that of the product ({"prod.billing": {"params": {...}}, "prod.billing.search": {"quotas": [...]}}).
     */
    default ProtocolMapperRepresentation toFeatureMapper(String catalog, String product, String feature) {
        return attributeMapper(CatalogRoles.featureQuotasAttribute(catalog, product, feature), true);
    }

    private static ProtocolMapperRepresentation attributeMapper(String attribute, boolean multivalued) {
        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", attribute);
        // a flat claim: {"prod.shop-repository": {"quotas": [...]}}
        config.put("claim.name", CatalogRoles.claimName(attribute));
        config.put("jsonType.label", "JSON");
        config.put("multivalued", String.valueOf(multivalued));
        config.put("aggregate.attrs", "true");
        config.put("access.token.claim", "true");
        config.put("introspection.token.claim", "true");
        // like the native mapper of the client roles: a lightweight token carries neither the roles nor the quotas,
        // the APIs read both at the introspection
        config.put("lightweight.claim", "false");
        config.put("id.token.claim", "false");
        config.put("userinfo.token.claim", "false");

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(attribute);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");
        mapper.setConfig(config);
        return mapper;
    }

    /**
     * Attributes of the assignment group /{org}/catalogs/{catalog}/{product}, read back by {@link #toOrgProduct}.
     *
     * @param params the values of the parameters, defaults applied
     */
    default Map<String, List<String>> toAssignmentAttributes(AssignProductRequest request, Map<String, String> params) {
        Map<String, List<String>> attributes = new HashMap<>();
        // checked by the service: a simple product has a quota, a metered one a quota per metered feature bought
        if (request.quota() != null && request.cycle() != null) {
            attributes.put(OrgGroups.QUOTA_ATTRIBUTE, List.of(request.quota().toString()));
            attributes.put(OrgGroups.QUOTA_CYCLE_ATTRIBUTE, List.of(request.cycle().getValue()));
        }
        request.featureQuotas().forEach((feature, quota) -> {
            attributes.put(OrgGroups.featureQuotaAttribute(feature), List.of(quota.quota().toString()));
            attributes.put(OrgGroups.featureQuotaCycleAttribute(feature), List.of(quota.cycle().getValue()));
        });
        if (!request.boughtFeatures().isEmpty()) {
            attributes.put(OrgGroups.BOUGHT_FEATURES_ATTRIBUTE, request.boughtFeatures().stream().distinct().sorted().toList());
        }
        params.forEach((name, value) -> attributes.put(OrgGroups.paramAttribute(name), List.of(value)));
        return attributes;
    }

    /** The group name is the product, the name of its parent the catalog. */
    default OrgProduct toOrgProduct(String catalog, GroupRepresentation group) {
        Map<String, List<String>> attributes = group.getAttributes() == null ? Map.of() : group.getAttributes();
        return new OrgProduct(catalog, group.getName(), group.getId(),
                parseQuota(firstValue(attributes, OrgGroups.QUOTA_ATTRIBUTE)),
                parseCycle(firstValue(attributes, OrgGroups.QUOTA_CYCLE_ATTRIBUTE)),
                attributes.getOrDefault(OrgGroups.BOUGHT_FEATURES_ATTRIBUTE, List.of()).stream().sorted().toList(),
                featureQuotas(attributes),
                paramValues(attributes));
    }

    /**
     * The quotas of the metered features stored on an assignment group, sorted by feature. A quota whose value or cycle
     * is missing or unreadable (changed outside of Orgkeep) is left out: the feature then has no pool.
     */
    default Map<String, FeatureQuota> featureQuotas(Map<String, List<String>> attributes) {
        Map<String, FeatureQuota> quotas = new TreeMap<>();
        attributes.keySet().forEach(attribute -> {
            String feature = OrgGroups.featureOfQuota(attribute);
            if (feature == null) {
                return;
            }
            Long quota = parseQuota(firstValue(attributes, attribute));
            QuotaCycle cycle = parseCycle(firstValue(attributes, OrgGroups.featureQuotaCycleAttribute(feature)));
            if (quota != null && cycle != null) {
                quotas.put(feature, new FeatureQuota(quota, cycle));
            }
        });
        return quotas;
    }

    /** The values of the parameters stored on an assignment group, sorted by name. */
    default Map<String, String> paramValues(Map<String, List<String>> attributes) {
        Map<String, String> values = new TreeMap<>();
        attributes.forEach((attribute, value) -> {
            String param = OrgGroups.paramOf(attribute);
            if (param != null && !value.isEmpty()) {
                values.put(param, value.get(0));
            }
        });
        return values;
    }

    private static Long parseQuota(String quota) {
        try {
            return quota == null ? null : Long.valueOf(quota);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static QuotaCycle parseCycle(String cycle) {
        return cycle == null ? null : QuotaCycle.fromValue(cycle).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
