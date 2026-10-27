package io.orgkeep.components.catalog.product;

import io.orgkeep.api.ProductService;
import io.orgkeep.api.exceptions.FeatureNotFoundException;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.ProductNotAssignedException;
import io.orgkeep.api.exceptions.ProductNotFoundException;
import io.orgkeep.api.exceptions.RoleNameTakenException;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.UpdateFeatureRequest;
import io.orgkeep.api.model.UpdateProductRequest;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.components.organisation.usergroup.team.TeamAlignment;
import io.orgkeep.components.organisation.usergroup.team.TeamClaims;
import io.orgkeep.repository.keycloak.KeycloakClientScopes;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    /** Same rule as the names in the request records, for a role name read from the URL. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final KeycloakGroups groups;
    private final KeycloakOrgs orgs;
    private final CatalogSetup catalogSetup;
    private final TeamAlignment teamAlignment;
    private final ProductMapper productMapper;

    @Override
    public Product create(String realm, String catalog, CreateProductRequest request) {
        ParamRules.checkSchema(request.params());
        Set<String> featureNames = new HashSet<>();
        request.features().forEach(feature -> {
            checkNotReserved(feature.name());
            if (!featureNames.add(feature.name())) {
                throw new InvalidRequestException("Feature '%s' is given twice".formatted(feature.name()));
            }
        });
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);

        RoleRepresentation product = productMapper.toRoleRepresentation(
                request.name(), request.displayName(), request.description(), request.params());
        if (!clients.createRole(realm, ids.clientUuid(), product)) {
            throw new RoleNameTakenException(catalog, request.name());
        }
        try {
            List<String> created = new ArrayList<>(List.of(request.name()));
            for (Feature feature : request.features()) {
                RoleRepresentation representation = productMapper.toRoleRepresentation(request.name(), feature);
                clients.createRole(realm, ids.clientUuid(), representation);
                created.add(representation.getName());
            }
            // the requesting clients have fullScopeAllowed=false: a role only reaches a token through these mappings
            scopes.addClientRolesToScope(realm, ids.scopeId(), ids.clientUuid(), created);
            productMapper.toMappers(catalog, request.name())
                    .forEach(mapper -> scopes.addMapper(realm, ids.scopeId(), mapper));
            request.features().stream()
                    .filter(Feature::metered)
                    .forEach(feature -> scopes.addMapper(realm, ids.scopeId(),
                            productMapper.toFeatureMapper(catalog, request.name(), feature.name())));
        } catch (RuntimeException e) {
            // admin API calls are not transactional: a product without its features or mappers must not stay behind
            remove(realm, catalog, ids, request.name());
            throw e;
        }
        log.info("Product '{}' created in catalog '{}' of realm '{}'", request.name(), catalog, realm);
        return get(realm, catalog, request.name());
    }

    @Override
    public List<Product> list(String realm, String catalog) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        // one call for every role of the catalog (attributes included), then one per product for its orgs
        List<RoleRepresentation> roles = clients.listRoles(realm, ids.clientUuid());
        return roles.stream()
                .filter(productMapper::isProduct)
                .sorted(Comparator.comparing(RoleRepresentation::getName))
                .map(product -> productMapper.toProduct(catalog, product, roles,
                        assignments(realm, catalog, ids.clientUuid(), product.getName()).size()))
                .toList();
    }

    @Override
    public Product get(String realm, String catalog, String product) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        List<RoleRepresentation> roles = clients.listRoles(realm, ids.clientUuid());
        RoleRepresentation role = roles.stream()
                .filter(r -> product.equals(r.getName()) && productMapper.isProduct(r))
                .findFirst()
                .orElseThrow(() -> new ProductNotFoundException(catalog, product));
        return productMapper.toProduct(catalog, role, roles, assignments(realm, catalog, ids.clientUuid(), product).size());
    }

    @Override
    public Product update(String realm, String catalog, String product, UpdateProductRequest request) {
        ParamRules.checkSchema(request.params());
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getProductRole(realm, catalog, ids, product);

        // the values of the orgs follow the new schema: all checked before anything changes
        Map<String, Map<String, List<String>>> newAttributes = new LinkedHashMap<>();
        Map<String, String> assignments = assignments(realm, catalog, ids.clientUuid(), product);
        Set<String> assignedOrgs = assignments.keySet();
        assignments.forEach((org, path) -> groups.findId(realm, path)
                .ifPresent(groupId -> newAttributes.put(groupId,
                        withParams(org, groups.attributes(realm, groupId), request.params()))));

        // Keycloak ignores a null field on update: an empty description removes the current one
        clients.updateRole(realm, ids.clientUuid(), product, productMapper.toRoleRepresentation(
                product, request.displayName(), request.description() == null ? "" : request.description(),
                request.params()));
        newAttributes.forEach((groupId, attributes) -> groups.replaceAttributes(realm, groupId, attributes));
        // the teams carry a copy of the parameter values of their org
        assignedOrgs.forEach(org -> teamAlignment.align(realm, org, catalog, product));
        log.info("Product '{}' of catalog '{}' updated in realm '{}'", product, catalog, realm);
        return get(realm, catalog, product);
    }

    @Override
    public void delete(String realm, String catalog, String product) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getProductRole(realm, catalog, ids, product);

        // the product role is granted to the assignment groups (and later to the teams): deleting it would silently
        // cut the access of these orgs, they must be unassigned first
        if (!clients.groupPathsWithRole(realm, ids.clientUuid(), product).isEmpty()) {
            throw new OperationNotAllowedException(
                    "Product '%s' is still assigned to organisations, unassign it first".formatted(product));
        }
        remove(realm, catalog, ids, product);
        log.info("Product '{}' deleted from catalog '{}' of realm '{}'", product, catalog, realm);
    }

    @Override
    public Product putFeature(String realm, String catalog, String product, String feature, UpdateFeatureRequest request) {
        if (!SLUG.matcher(feature).matches() || feature.length() > 50) {
            throw new InvalidRequestException("Feature name '%s': lowercase letters, digits and inner hyphens only".formatted(feature));
        }
        checkNotReserved(feature);
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getProductRole(realm, catalog, ids, product);

        String displayName = request == null ? null : request.displayName();
        String roleName = CatalogRoles.feature(product, feature);
        List<RoleRepresentation> roles = clients.listRoles(realm, ids.clientUuid());
        Optional<RoleRepresentation> existing = roles.stream()
                .filter(role -> roleName.equals(role.getName()))
                .findFirst();
        if (existing.isPresent()) {
            // metered is set at creation and kept: only the display name changes. Turning a bought feature into a
            // metered one would leave the orgs without a quota for it
            boolean metered = productMapper.toFeature(product, existing.get()).metered();
            if (request != null && request.metered() != null && request.metered() != metered) {
                throw new OperationNotAllowedException(
                        "Feature '%s' of the product '%s' is %s: this is set when a feature is created, delete and recreate it"
                                .formatted(feature, product, metered ? "metered" : "not metered"));
            }
            RoleRepresentation representation = productMapper.toRoleRepresentation(product,
                    new Feature(feature, displayName, metered));
            clients.updateRole(realm, ids.clientUuid(), representation.getName(), representation);
        } else {
            boolean metered = request != null && Boolean.TRUE.equals(request.metered());
            // the first metered feature changes the kind of the product: each org would go from a quota of product
            // to quotas per feature. Free while nobody has it, or if the product is already metered
            if (metered && !productMapper.isMetered(roles, product) && isAssigned(realm, catalog, ids, product)) {
                throw new OperationNotAllowedException(
                        "Product '%s' is assigned to organisations and has no metered feature: a first one would change its quotas. Create a new product instead"
                                .formatted(product));
            }
            RoleRepresentation representation = productMapper.toRoleRepresentation(product,
                    new Feature(feature, displayName, metered));
            clients.createRole(realm, ids.clientUuid(), representation);
            scopes.addClientRolesToScope(realm, ids.scopeId(), ids.clientUuid(), List.of(representation.getName()));
            if (metered) {
                scopes.addMapper(realm, ids.scopeId(), productMapper.toFeatureMapper(catalog, product, feature));
            }
            log.info("Feature '{}' added to product '{}' of catalog '{}' in realm '{}'", feature, product, catalog, realm);
        }
        return get(realm, catalog, product);
    }

    @Override
    public Product deleteFeature(String realm, String catalog, String product, String feature) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getProductRole(realm, catalog, ids, product);
        String roleName = CatalogRoles.feature(product, feature);
        List<RoleRepresentation> roles = clients.listRoles(realm, ids.clientUuid());
        RoleRepresentation role = roles.stream()
                .filter(r -> roleName.equals(r.getName()) && productMapper.isFeatureOf(r, product))
                .findFirst()
                .orElseThrow(() -> new FeatureNotFoundException(catalog, product, feature));
        Map<String, String> assignments = assignments(realm, catalog, ids.clientUuid(), product);
        boolean metered = productMapper.toFeature(product, role).metered();
        if (metered && !assignments.isEmpty()) {
            checkMeteredFeatureCanGo(realm, product, feature, roles, assignments);
        }

        // Keycloak removes the role from the groups holding it: the teams lose it at once
        clients.deleteRole(realm, ids.clientUuid(), roleName);
        if (metered) {
            scopes.removeMappers(realm, ids.scopeId(),
                    List.of(productMapper.toFeatureMapper(catalog, product, feature).getName()));
        }
        // the orgs that bought it no longer have it, and their teams that listed it forget it
        assignments.forEach((org, path) -> {
            groups.findId(realm, path).ifPresent(groupId -> {
                Map<String, List<String>> attributes = new HashMap<>(groups.attributes(realm, groupId));
                List<String> bought = new ArrayList<>(attributes.getOrDefault(OrgGroups.BOUGHT_FEATURES_ATTRIBUTE, List.of()));
                if (bought.remove(feature)) {
                    attributes.put(OrgGroups.BOUGHT_FEATURES_ATTRIBUTE, bought);
                    groups.replaceAttributes(realm, groupId, attributes);
                }
            });
            teamAlignment.forgetFeature(realm, org, catalog, product, feature);
        });
        log.info("Feature '{}' removed from product '{}' of catalog '{}' in realm '{}'", feature, product, catalog, realm);
        return get(realm, catalog, product);
    }

    @Override
    public OrgProduct assign(String realm, String orgName, String catalog, String product, AssignProductRequest request) {
        orgs.getByName(realm, orgName);
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        RoleRepresentation productRole = getProductRole(realm, catalog, ids, product);

        Map<String, Feature> features = new HashMap<>();
        clients.listRoles(realm, ids.clientUuid()).stream()
                .filter(role -> productMapper.isFeatureOf(role, product))
                .map(role -> productMapper.toFeature(product, role))
                .forEach(feature -> features.put(feature.name(), feature));
        List<String> unknown = request.boughtFeatures().stream().filter(feature -> !features.containsKey(feature)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Not features of the product '%s': %s".formatted(product, unknown));
        }
        checkQuotas(product, features.values(), request);
        List<ParamDefinition> schema = productMapper.params(productRole.getAttributes());
        Map<String, String> params = ParamRules.resolveValues(schema, request.params());
        // the values are copied on the teams as one JSON value, which must fit in a group attribute
        TeamClaims.checkParamsFit(schema, params);
        Map<String, List<String>> attributes = productMapper.toAssignmentAttributes(request, params);

        Optional<String> existingId = groups.findId(realm, OrgGroups.assignment(orgName, catalog, product));
        String groupId;
        if (existingId.isPresent()) {
            // already assigned: a new negotiation replaces the values
            groupId = existingId.get();
            groups.replaceAttributes(realm, groupId, attributes);
            log.info("Product '{}' of catalog '{}' renegotiated by organisation '{}' in realm '{}'", product, catalog, orgName, realm);
        } else {
            String catalogGroupId = groups.findId(realm, OrgGroups.catalog(orgName, catalog))
                    .orElseGet(() -> groups.createChild(realm, catalogsGroupId(realm, orgName), catalog));
            groupId = groups.createChild(realm, catalogGroupId, product, attributes);
            try {
                clients.grantToGroup(realm, ids.clientUuid(), groupId, product);
            } catch (RuntimeException e) {
                // admin API calls are not transactional: a group without the product role must not stay behind
                groups.delete(realm, groupId);
                throw e;
            }
            log.info("Product '{}' of catalog '{}' assigned to organisation '{}' in realm '{}'", product, catalog, orgName, realm);
        }
        // the teams giving the product follow what the org negotiated: quota, bought features, parameters
        teamAlignment.align(realm, orgName, catalog, product);
        return new OrgProduct(catalog, product, groupId, request.quota(), request.cycle(),
                request.boughtFeatures().stream().distinct().sorted().toList(), new TreeMap<>(request.featureQuotas()), params);
    }

    /**
     * The quotas an assignment needs, after the kind of the product: a quota of product for a simple product, a quota
     * per metered feature bought for a metered one, and nothing else.
     *
     * @param features the features of the product
     * @throws InvalidRequestException naming what is missing or in excess
     */
    private static void checkQuotas(String product, Collection<Feature> features, AssignProductRequest request) {
        boolean metered = features.stream().anyMatch(Feature::metered);
        if (!metered) {
            if (request.quota() == null || request.cycle() == null) {
                throw new InvalidRequestException("Product '%s' needs a quota and its cycle".formatted(product));
            }
            if (!request.featureQuotas().isEmpty()) {
                throw new InvalidRequestException(
                        "Product '%s' has no metered feature: no quota per feature, only a quota of product".formatted(product));
            }
            return;
        }
        if (request.quota() != null || request.cycle() != null) {
            throw new InvalidRequestException(
                    "Product '%s' has metered features: no quota of product, a quota per metered feature bought".formatted(product));
        }
        // without an access role in the tokens, a product without any feature would give nothing
        if (request.boughtFeatures().isEmpty()) {
            throw new InvalidRequestException("Product '%s' has metered features: at least one feature must be bought".formatted(product));
        }
        Set<String> boughtMetered = new TreeSet<>();
        features.stream()
                .filter(feature -> feature.metered() && request.boughtFeatures().contains(feature.name()))
                .forEach(feature -> boughtMetered.add(feature.name()));
        List<String> missing = boughtMetered.stream()
                .filter(feature -> !request.featureQuotas().containsKey(feature))
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidRequestException(
                    "Metered features of the product '%s' bought without a quota: %s".formatted(product, missing));
        }
        List<String> extra = request.featureQuotas().keySet().stream()
                .filter(feature -> !boughtMetered.contains(feature))
                .sorted()
                .toList();
        if (!extra.isEmpty()) {
            throw new InvalidRequestException(
                    "Quotas for features of the product '%s' that are not metered features bought: %s".formatted(product, extra));
        }
    }

    @Override
    public void unassign(String realm, String orgName, String catalog, String product) {
        orgs.getByName(realm, orgName);
        String groupId = groups.findId(realm, OrgGroups.assignment(orgName, catalog, product))
                .orElseThrow(() -> new ProductNotAssignedException(orgName, catalog, product));

        groups.delete(realm, groupId);
        // the teams no longer give it, and without any product in the catalog, no catalog role of it either
        teamAlignment.revoke(realm, orgName, catalog, product);
        if (!teamAlignment.hasAssignment(realm, orgName, catalog)) {
            teamAlignment.revokeCatalogRoles(realm, orgName, catalog);
        }
        log.info("Product '{}' of catalog '{}' unassigned from organisation '{}' in realm '{}'", product, catalog, orgName, realm);
    }

    @Override
    public List<OrgProduct> listAssigned(String realm, String orgName) {
        orgs.getByName(realm, orgName);
        // one call for the catalogs of the org, then one per catalog for its products
        return groups.children(realm, catalogsGroupId(realm, orgName)).stream()
                .sorted(Comparator.comparing(GroupRepresentation::getName))
                .flatMap(catalogGroup -> groups.children(realm, catalogGroup.getId()).stream()
                        .sorted(Comparator.comparing(GroupRepresentation::getName))
                        .map(group -> productMapper.toOrgProduct(catalogGroup.getName(), group)))
                .toList();
    }

    /** The role of the product, checked to be a product (not a catalog role of the same client). */
    private RoleRepresentation getProductRole(String realm, String catalog, CatalogSetup.Ids ids, String product) {
        return clients.findRole(realm, ids.clientUuid(), product)
                .filter(productMapper::isProduct)
                .orElseThrow(() -> new ProductNotFoundException(catalog, product));
    }

    /**
     * A metered feature of an assigned product only goes if no org bought it (it carries a negotiated quota, removing it
     * would silently cut a capacity), and if it is not the last one (the kind of the product would change).
     *
     * @param roles       every role of the catalog client
     * @param assignments the orgs the product is assigned to, not empty
     */
    private void checkMeteredFeatureCanGo(String realm, String product, String feature, List<RoleRepresentation> roles,
                                          Map<String, String> assignments) {
        List<String> buyers = assignments.entrySet().stream()
                .filter(assignment -> groups.findId(realm, assignment.getValue())
                        .map(groupId -> groups.attributes(realm, groupId)
                                .getOrDefault(OrgGroups.BOUGHT_FEATURES_ATTRIBUTE, List.of())
                                .contains(feature))
                        .orElse(false))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!buyers.isEmpty()) {
            throw new OperationNotAllowedException(
                    "Metered feature '%s' of the product '%s' is bought by organisations, with a quota: %s"
                            .formatted(feature, product, buyers));
        }
        String roleName = CatalogRoles.feature(product, feature);
        boolean last = roles.stream()
                .filter(role -> productMapper.isFeatureOf(role, product) && !roleName.equals(role.getName()))
                .noneMatch(role -> productMapper.toFeature(product, role).metered());
        if (last) {
            throw new OperationNotAllowedException(
                    "Feature '%s' is the last metered feature of the product '%s', which is assigned to organisations: without it, its quotas would change. Create a new product instead"
                            .formatted(feature, product));
        }
    }

    /**
     * @throws InvalidRequestException if the name of the feature is a key of the object of its product in the tokens
     */
    private static void checkNotReserved(String feature) {
        if (CatalogRoles.RESERVED_FEATURE_NAMES.contains(feature)) {
            throw new InvalidRequestException("Feature name '%s' is reserved, as are %s"
                    .formatted(feature, new TreeSet<>(CatalogRoles.RESERVED_FEATURE_NAMES)));
        }
    }

    private boolean isAssigned(String realm, String catalog, CatalogSetup.Ids ids, String product) {
        return !assignments(realm, catalog, ids.clientUuid(), product).isEmpty();
    }

    /** Org name → path of its assignment group, for the orgs the product is assigned to. */
    private Map<String, String> assignments(String realm, String catalog, String clientUuid, String product) {
        Map<String, String> assignments = new HashMap<>();
        for (String path : clients.groupPathsWithRole(realm, clientUuid, product)) {
            String org = OrgGroups.orgOfAssignment(path, catalog, product);
            if (org != null) {
                assignments.put(org, path);
            }
        }
        return assignments;
    }

    /**
     * The attributes of an assignment group with its parameter values fitted to a new schema: a removed parameter
     * goes, a new one gets its default.
     *
     * @throws InvalidRequestException naming the org if its values do not fit
     */
    private Map<String, List<String>> withParams(String org, Map<String, List<String>> attributes,
                                                 List<ParamDefinition> schema) {
        Set<String> names = new HashSet<>();
        schema.forEach(param -> names.add(param.name()));
        Map<String, String> kept = new HashMap<>(productMapper.paramValues(attributes));
        kept.keySet().retainAll(names);

        Map<String, String> resolved;
        try {
            resolved = ParamRules.resolveValues(schema, kept);
            TeamClaims.checkParamsFit(schema, resolved);
        } catch (InvalidRequestException e) {
            throw new InvalidRequestException("Organisation '%s': %s".formatted(org, e.getMessage()));
        }
        Map<String, List<String>> updated = new HashMap<>();
        attributes.forEach((attribute, values) -> {
            if (OrgGroups.paramOf(attribute) == null) {
                updated.put(attribute, values);
            }
        });
        resolved.forEach((name, value) -> updated.put(OrgGroups.paramAttribute(name), List.of(value)));
        return updated;
    }

    /** Deletes what exists of the product: its features, its mappers and those of its features, its role. */
    private void remove(String realm, String catalog, CatalogSetup.Ids ids, String product) {
        List<String> mappers = new ArrayList<>(productMapper.toMappers(catalog, product).stream()
                .map(ProtocolMapperRepresentation::getName)
                .toList());
        clients.listRoles(realm, ids.clientUuid()).stream()
                .filter(role -> productMapper.isFeatureOf(role, product))
                .forEach(role -> {
                    clients.deleteRole(realm, ids.clientUuid(), role.getName());
                    // a feature that is not metered has no mapper: its name is simply not found
                    String feature = productMapper.toFeature(product, role).name();
                    mappers.add(productMapper.toFeatureMapper(catalog, product, feature).getName());
                });
        scopes.removeMappers(realm, ids.scopeId(), mappers);
        if (clients.findRole(realm, ids.clientUuid(), product).isPresent()) {
            clients.deleteRole(realm, ids.clientUuid(), product);
        }
    }

    private String catalogsGroupId(String realm, String orgName) {
        // an org created outside of Orgkeep has no group tree
        return groups.findId(realm, OrgGroups.catalogs(orgName))
                .orElseThrow(() -> new OperationNotAllowedException(
                        "Organisation '%s' has no group %s: it was not created by Orgkeep"
                                .formatted(orgName, OrgGroups.catalogs(orgName))));
    }
}
