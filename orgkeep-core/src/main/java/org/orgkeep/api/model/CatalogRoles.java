package org.orgkeep.api.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keycloak conventions of the roles of a catalog client (see {@link Catalogs}). Three kinds of client roles, told apart
 * by the attribute {@value #TYPE}:
 * <ul>
 *   <li>a <b>product</b> ("shop-repository"): its presence in resource_access.&lt;catalog&gt; says the user has the
 *       product. Its parameter schema is in its attributes ({@link #paramAttribute});</li>
 *   <li>a <b>feature</b> ("shop-repository:search"): a permission, or a capacity an org buys with a quota if it is
 *       {@value #METERED}. A real client role, named after its product, so that it reaches resource_access natively;</li>
 *   <li>a <b>catalog role</b> ("searcher"): valid for every product of the catalog, granted by the org admins
 *       ({@value #ORG_GRANTABLE}) or reserved to the realm owner.</li>
 * </ul>
 * Products and catalog roles share the role names of the client. No JSON in the attributes: Keycloak stores role and
 * group attribute values in 255 characters, hence one short value per element and prefixed keys.
 * <p>
 * Each product has two native "User Attribute" mappers on the scope of its catalog, reading the attributes
 * {@link #quotasAttribute} and {@link #paramsAttribute} on the groups of the user (the teams giving the product), and
 * each metered feature one more, reading {@link #featureQuotasAttribute}. Each one writes a flat claim
 * ({@link #claimName}): a top-level object per product and per metered feature, {"prod.billing": {"params": {...}},
 * "prod.billing.search": {"quotas": [...]}}, so that an API reads what it counts with a single claim name.
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

    /**
     * Names a feature cannot take: they would read as a key of the product in the attributes ("prod.billing.params"
     * next to "prod.billing.search.quotas"). Some are not used yet, kept for later options.
     */
    public static final Set<String> RESERVED_FEATURE_NAMES = Set.of("params", "options", "quotas");

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

    /** Client role of a feature, e.g. "shop-repository:search". */
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
     * Attribute of the quota pools of a simple product, e.g. "prod.shop-repository.quotas", written in the token as
     * {"prod.shop-repository": {"quotas": [...]}} ({@link #claimName}).
     */
    public static String quotasAttribute(String catalog, String product) {
        return productClaim(catalog, product) + ".quotas";
    }

    /**
     * Attribute of the quota pools of a metered feature, e.g. "prod.billing.search.quotas", written in the token as an
     * object of its own, {"prod.billing.search": {"quotas": [...]}} ({@link #claimName}).
     */
    public static String featureQuotasAttribute(String catalog, String product, String feature) {
        return productClaim(catalog, product) + "." + feature + ".quotas";
    }

    /** Attribute of the parameter values of a product, e.g. "prod.shop-repository.params". */
    public static String paramsAttribute(String catalog, String product) {
        return productClaim(catalog, product) + ".params";
    }

    /**
     * True for a derived attribute of the product read by its mappers: its quotas, its parameters, the quotas of one of
     * its features.
     */
    public static boolean isClaimOf(String attribute, String catalog, String product) {
        return attribute.startsWith(productClaim(catalog, product) + ".");
    }

    /**
     * The claim name of a mapper reading a derived attribute: Keycloak turns the dots of a claim name into nested
     * objects, unless escaped. Every dot but the last is escaped, "prod.billing.search.quotas" gives
     * "prod\.billing\.search.quotas", written as {"prod.billing.search": {"quotas": [...]}}. Names are slugs, never
     * with a dot of their own.
     */
    public static String claimName(String attribute) {
        int last = attribute.lastIndexOf('.');
        return attribute.substring(0, last).replace(".", "\\.") + attribute.substring(last);
    }

    /** The prefix of the derived attributes of a product, e.g. "prod.billing". */
    private static String productClaim(String catalog, String product) {
        return catalog + "." + product;
    }
}
