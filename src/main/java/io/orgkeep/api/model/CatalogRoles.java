package io.orgkeep.api.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keycloak conventions of the roles of a catalog client (see {@link Catalogs}). Three kinds of client roles, told apart
 * by the attribute {@value #TYPE}:
 * <ul>
 *   <li>a <b>product</b> ("shop-repository"): its presence in resource_access.&lt;catalog&gt; says the user has the
 *       product. Its parameter schema is in its attributes ({@link #paramAttribute});</li>
 *   <li>a <b>feature</b> ("shop-repository:scan"): a permission, or a capacity an org buys with a quota if it is
 *       {@value #METERED}. A real client role, named after its product, so that it reaches resource_access natively;</li>
 *   <li>a <b>catalog role</b> ("scanner"): valid for every product of the catalog, granted by the org admins
 *       ({@value #ORG_GRANTABLE}) or reserved to the realm owner.</li>
 * </ul>
 * Products and catalog roles share the role names of the client. No JSON in the attributes: Keycloak stores role and
 * group attribute values in 255 characters, hence one short value per element and prefixed keys.
 * <p>
 * Each product has two native "User Attribute" mappers on the scope of its catalog, reading the attributes
 * {@link #quotasAttribute} and {@link #paramsAttribute} on the groups of the user (the teams giving the product).
 */
public final class CatalogRoles {
    public static final String TYPE = "type";
    public static final String TYPE_PRODUCT = "product";
    public static final String TYPE_FEATURE = "feature";
    public static final String TYPE_CATALOG_ROLE = "catalog-role";

    public static final String DISPLAY_NAME = "display-name";
    /** Attribute of a feature: the name of its product. */
    public static final String PRODUCT = "product";
    /** Attribute of a feature: "true" if the org buys it with its own quota. */
    public static final String METERED = "metered";
    /** Attribute of a catalog role: "true" if the org admins may grant it through their teams. */
    public static final String ORG_GRANTABLE = "org-grantable";

    /** Between a product and one of its features: never in a slug, so a feature never clashes with another role. */
    public static final String SEPARATOR = ":";

    /** Parameters every product has, not part of its schema: the quota of the org and its cycle. */
    public static final Set<String> STANDARD_PARAMS = Set.of("quota", "quota-cycle");

    private static final String PARAM_PREFIX = "param.";
    public static final String PARAM_TYPE = "type";
    public static final String PARAM_REQUIRED = "required";
    public static final String PARAM_DEFAULT = "default";
    public static final String PARAM_VALUES = "values";

    private CatalogRoles() {
    }

    /** The kind of a role of a catalog client, read from its attributes (null for a role created outside Orgkeep). */
    public static String typeOf(Map<String, List<String>> attributes) {
        return firstValue(attributes, TYPE);
    }

    /** First value of a role or group attribute, null if absent. */
    public static String firstValue(Map<String, List<String>> attributes, String name) {
        if (attributes == null) {
            return null;
        }
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Client role of a feature, e.g. "shop-repository:scan". */
    public static String feature(String product, String feature) {
        return product + SEPARATOR + feature;
    }

    /** Attribute of a product holding a field of the definition of one of its parameters, e.g. "param.region.type". */
    public static String paramAttribute(String param, String field) {
        return PARAM_PREFIX + param + "." + field;
    }

    /** The parameter of a definition attribute ("param.region.type" gives "region"), null for another attribute. */
    public static String paramOfDefinition(String attribute) {
        String suffix = "." + PARAM_TYPE;
        if (!attribute.startsWith(PARAM_PREFIX) || !attribute.endsWith(suffix)) {
            return null;
        }
        return attribute.substring(PARAM_PREFIX.length(), attribute.length() - suffix.length());
    }

    /**
     * Attribute and claim of the quota pools of a product, e.g. "prod.shop-repository.quotas": Keycloak turns the dots
     * of a claim name into nested objects, {"prod": {"shop-repository": {"quotas": [...]}}}.
     */
    public static String quotasAttribute(String catalog, String product) {
        return catalog + "." + product + ".quotas";
    }

    /** Attribute and claim of the parameter values of a product, e.g. "prod.shop-repository.params". */
    public static String paramsAttribute(String catalog, String product) {
        return catalog + "." + product + ".params";
    }
}
