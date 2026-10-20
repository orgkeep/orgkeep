package io.orgkeep.api.model;

import java.util.Set;

/**
 * Keycloak conventions of the catalogs.
 * <p>
 * A catalog is a client, a container of roles like the org clients, whose clientId the realm owner chooses with its own
 * criterion (environment "products" / "products-sandbox", region, offer...). A client scope of the same name, an optional
 * scope of the realm, maps the roles of the catalog and carries the claims of its products: a token only holds the
 * catalog when its client has the scope by default or the application asks for it (scope=&lt;catalog&gt;). The name of a
 * catalog ends up as a claim name in the access tokens, hence {@link #isReserved}.
 * <p>
 * Catalogs are told apart by the attribute {@value #MARKER_ATTRIBUTE} of their client scope. Not of their client:
 * the admin API does not search clients by attribute (unless configured in Keycloak), and a realm has many clients
 * (one per org, later the service accounts), whereas client scopes are few and listed in one call.
 */
public final class Catalogs {
    /** The catalog created with the realm: a realm owner without a particular need has nothing to configure. */
    public static final String DEFAULT = "products";

    /** Attribute marking the client scopes of the catalogs, neutral on purpose like every name visible in Keycloak. */
    public static final String MARKER_ATTRIBUTE = "catalog";
    public static final String MARKER_VALUE = "true";

    /**
     * Claims Keycloak or OIDC put at the top level of an access token: a catalog of the same name would clash with them.
     * Only the names allowed by the slug pattern of a catalog are listed (no "realm_access" or "auth_time").
     */
    private static final Set<String> RESERVED_CLAIMS = Set.of(
            "sub", "aud", "iss", "exp", "iat", "nbf", "jti", "azp", "typ", "sid", "acr", "amr", "scope", "nonce",
            "name", "email", "locale", "address", "groups", "roles", "organization", "allowed-origins",
            "picture", "profile", "website", "gender", "birthdate", "nickname", "zoneinfo", "cnf");

    private Catalogs() {
    }

    /** A name that a catalog cannot take: a reserved claim, or the prefix of the org clients. */
    public static boolean isReserved(String name) {
        return name.startsWith(OrgClients.PREFIX) || RESERVED_CLAIMS.contains(name);
    }
}
