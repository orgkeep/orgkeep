package io.orgkeep.api.model;

/**
 * Keycloak conventions of what a team (a user group, /{org}/user-groups/{team}) gives. Two kinds of attributes on the
 * group of the team:
 * <ul>
 *   <li>the <b>choices</b> of the org admin, prefixed with {@value #PREFIX}: the source of truth, kept to realign the
 *       team when the assignment of the product changes;</li>
 *   <li>what is <b>derived</b> from them and from the assignment, read by the native mappers of the product
 *       ({@link CatalogRoles#quotasAttribute}, {@link CatalogRoles#paramsAttribute}), next to the role mappings of the
 *       group (the product, its features granted ∩ bought, the catalog roles).</li>
 * </ul>
 * <pre>
 * grant:prod:shop-repository              = all | listed   the team gives the product
 * grant:prod:shop-repository:features     = scan, ...      if listed: the features asked for (absent: none)
 * grant:prod:shop-repository:quota        = 300            optional quota of the team
 * grant:prod:shop-repository:quota-cycle  = monthly        its cycle
 * </pre>
 * The separator ":" is never in a slug: a choice never clashes with a derived attribute ("prod.shop-repository.quotas"),
 * whatever the names of the catalogs and products.
 */
public final class TeamGrants {
    public static final String PREFIX = "grant:";
    private static final String SEPARATOR = ":";

    /** Value of {@link #product}: every feature the org bought, those it buys later included. */
    public static final String ALL_FEATURES = "all";
    /** Value of {@link #product}: only the features in {@link #features}, among those bought. */
    public static final String LISTED_FEATURES = "listed";

    private TeamGrants() {
    }

    /** Attribute saying the team gives the product, e.g. "grant:prod:shop-repository". */
    public static String product(String catalog, String product) {
        return PREFIX + catalog + SEPARATOR + product;
    }

    /** Multivalued attribute of the features of the product the team gives, without the product prefix. */
    public static String features(String catalog, String product) {
        return product(catalog, product) + SEPARATOR + "features";
    }

    public static String quota(String catalog, String product) {
        return product(catalog, product) + SEPARATOR + "quota";
    }

    public static String quotaCycle(String catalog, String product) {
        return product(catalog, product) + SEPARATOR + "quota-cycle";
    }

    /**
     * The catalog and the product of a {@link #product} attribute ("grant:prod:billing" gives ["prod", "billing"]),
     * null for another attribute (the features or quota of a grant included).
     */
    public static String[] productOf(String attribute) {
        if (!attribute.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = attribute.substring(PREFIX.length()).split(SEPARATOR);
        return parts.length == 2 ? parts : null;
    }

    /** Key of the pool of the org in the tokens: its name. */
    public static String orgPool(String orgName) {
        return orgName;
    }

    /** Key of the pool of a team in the tokens, e.g. "acme/ops": stable, a team recreated with the same name gets it back. */
    public static String teamPool(String orgName, String team) {
        return orgName + "/" + team;
    }
}
