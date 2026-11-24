package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Replaces the redirect URIs and web origins of the login client: these lists are required, an empty list removes them all.
 * defaultCatalogs is optional: absent, the default catalogs do not change; given, it replaces them (an empty list
 * leaves the client without default catalog, the applications then ask for scope=&lt;catalog&gt;).
 * lightweightTokens is optional too: absent, the mode does not change (see {@link LightweightTokens}). Turned on, every
 * API of the realm owner receives lightweight tokens at once: each must introspect first.
 * homeUrl is optional too, same convention as defaultCatalogs: absent (null), unchanged; empty string, removed;
 * a value, replaces it. It becomes the client's baseUrl, where Keycloak's "Back to Application" link points to on
 * its last page after an invitation (see members.md).
 * <p>
 * Keycloak accepts much more (a lone "*", any scheme), which would let an attacker receive the authorization code
 * on a site of their own. Only safe values are accepted here:
 * <ul>
 *   <li>https, or http on localhost / 127.0.0.1 only (development);</li>
 *   <li>no user info ("user@host"), which can disguise the real host;</li>
 *   <li>a wildcard only as the last path segment ("https://app.example.com/*"): "https://app.example.com*" would also
 *       match "https://app.example.com.evil.com".</li>
 * </ul>
 * homeUrl never enters an OAuth flow (no redirect_uri is sent), but is validated the same way (https or local http,
 * no user info), without wildcard, query or fragment: it only ever becomes the href of a link.
 */
public record UpdateLoginClientRequest(
    @NotNull
    List<@NotNull @Pattern(regexp = REDIRECT_URI, message = REDIRECT_URI_MESSAGE) String> redirectUris,
    @NotNull
    List<@NotNull @Pattern(regexp = REDIRECT_URI, message = REDIRECT_URI_MESSAGE) String> postLogoutRedirectUris,
    @NotNull
    List<@NotNull @Pattern(regexp = WEB_ORIGIN, message = WEB_ORIGIN_MESSAGE) String> webOrigins,
    List<@NotBlank String> defaultCatalogs,
    Boolean lightweightTokens,
    @Pattern(regexp = HOME_URL, message = HOME_URL_MESSAGE) String homeUrl){

    /** Without defaultCatalogs, lightweightTokens nor homeUrl: none of them change. */
    public UpdateLoginClientRequest(List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> webOrigins) {
        this(redirectUris, postLogoutRedirectUris, webOrigins, null, null, null);
    }

    /** Without lightweightTokens nor homeUrl: neither changes. */
    public UpdateLoginClientRequest(List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> webOrigins,
                                    List<String> defaultCatalogs) {
        this(redirectUris, postLogoutRedirectUris, webOrigins, defaultCatalogs, null, null);
    }

    /** Without homeUrl: it does not change. */
    public UpdateLoginClientRequest(List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> webOrigins,
                                    List<String> defaultCatalogs, Boolean lightweightTokens) {
        this(redirectUris, postLogoutRedirectUris, webOrigins, defaultCatalogs, lightweightTokens, null);
    }

    /** Scheme and host (and port): https anywhere, http on the local machine only. */
    public static final String ORIGIN = "(https://[^/?#@*\\s:]+|http://(localhost|127\\.0\\.0\\.1))(:\\d{1,5})?";

    /** An origin, then an optional path without wildcard, or a path ending with "/*". */
    static final String REDIRECT_URI = ORIGIN + "((/[^*\\s]*)?|/([^*\\s]*/)?\\*)";
    static final String REDIRECT_URI_MESSAGE =
            "https URL (http on localhost only), a wildcard is only allowed as the last path segment (/*)";

    /** An origin without path, or "+" (Keycloak: the origins of the redirect URIs). */
    static final String WEB_ORIGIN = "\\+|" + ORIGIN;
    static final String WEB_ORIGIN_MESSAGE = "https origin without path (http on localhost only), or +";

    /** Empty (removes the home URL), or an origin followed by an optional path, without wildcard, query or fragment. */
    static final String HOME_URL = "|" + ORIGIN + "(/[^*?#\\s]*)?";
    static final String HOME_URL_MESSAGE =
            "empty string, or https URL (http on localhost only), without wildcard, query or fragment";
}
