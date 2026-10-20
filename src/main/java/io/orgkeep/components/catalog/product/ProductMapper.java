package io.orgkeep.components.catalog.product;

import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.ProductRole;
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

    /** The client role of a role of a product, e.g. "shop-repository:scan". */
    default RoleRepresentation toRoleRepresentation(String product, ProductRole productRole) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put(CatalogRoles.TYPE, List.of(CatalogRoles.TYPE_PRODUCT_ROLE));
        attributes.put(CatalogRoles.PRODUCT, List.of(product));
        if (productRole.displayName() != null) {
            attributes.put(CatalogRoles.DISPLAY_NAME, List.of(productRole.displayName()));
        }
        RoleRepresentation role = new RoleRepresentation(CatalogRoles.productRole(product, productRole.name()), null, false);
        role.setAttributes(attributes);
        return role;
    }

    /**
     * @param roles    every role of the catalog client, full representations: the roles of this product are picked out
     * @param orgCount number of orgs the product is assigned to
     */
    default Product toProduct(String catalog, RoleRepresentation product, List<RoleRepresentation> roles, int orgCount) {
        List<ProductRole> productRoles = roles.stream()
                .filter(role -> isRoleOf(role, product.getName()))
                .map(role -> new ProductRole(
                        role.getName().substring(product.getName().length() + CatalogRoles.SEPARATOR.length()),
                        firstValue(role.getAttributes(), CatalogRoles.DISPLAY_NAME)))
                .sorted(Comparator.comparing(ProductRole::name))
                .toList();
        return new Product(catalog, product.getName(), firstValue(product.getAttributes(), CatalogRoles.DISPLAY_NAME),
                blankToNull(product.getDescription()), productRoles, params(product.getAttributes()), orgCount);
    }

    default boolean isProduct(RoleRepresentation role) {
        return CatalogRoles.TYPE_PRODUCT.equals(CatalogRoles.typeOf(role.getAttributes()));
    }

    default boolean isRoleOf(RoleRepresentation role, String product) {
        return CatalogRoles.TYPE_PRODUCT_ROLE.equals(CatalogRoles.typeOf(role.getAttributes()))
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
     *   <li>quotas: multivalued, one JSON object per pool. Keycloak keeps a value given by several teams once;</li>
     *   <li>params: a single JSON object.</li>
     * </ul>
     * Named after their attribute: unique in the scope, and found back when the product is deleted.
     */
    default List<ProtocolMapperRepresentation> toMappers(String catalog, String product) {
        return List.of(
                attributeMapper(CatalogRoles.quotasAttribute(catalog, product), true),
                attributeMapper(CatalogRoles.paramsAttribute(catalog, product), false));
    }

    private static ProtocolMapperRepresentation attributeMapper(String attribute, boolean multivalued) {
        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", attribute);
        // the dots of the claim name make nested objects: {"prod": {"shop-repository": {"quotas": [...]}}}
        config.put("claim.name", attribute);
        config.put("jsonType.label", "JSON");
        config.put("multivalued", String.valueOf(multivalued));
        config.put("aggregate.attrs", "true");
        config.put("access.token.claim", "true");
        config.put("introspection.token.claim", "true");
        config.put("lightweight.claim", "true");
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
        attributes.put(OrgGroups.QUOTA_ATTRIBUTE, List.of(request.quota().toString()));
        attributes.put(OrgGroups.QUOTA_CYCLE_ATTRIBUTE, List.of(request.cycle().getValue()));
        if (!request.boughtRoles().isEmpty()) {
            attributes.put(OrgGroups.BOUGHT_ROLES_ATTRIBUTE, request.boughtRoles().stream().distinct().sorted().toList());
        }
        params.forEach((name, value) -> attributes.put(OrgGroups.paramAttribute(name), List.of(value)));
        return attributes;
    }

    /** The group name is the product, the name of its parent the catalog. */
    default OrgProduct toOrgProduct(String catalog, GroupRepresentation group) {
        Map<String, List<String>> attributes = group.getAttributes() == null ? Map.of() : group.getAttributes();
        String cycle = firstValue(attributes, OrgGroups.QUOTA_CYCLE_ATTRIBUTE);
        return new OrgProduct(catalog, group.getName(), group.getId(),
                parseQuota(firstValue(attributes, OrgGroups.QUOTA_ATTRIBUTE)),
                cycle == null ? null : QuotaCycle.fromValue(cycle).orElse(null),
                attributes.getOrDefault(OrgGroups.BOUGHT_ROLES_ATTRIBUTE, List.of()).stream().sorted().toList(),
                paramValues(attributes));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
