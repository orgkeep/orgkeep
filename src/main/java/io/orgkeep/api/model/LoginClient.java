package io.orgkeep.api.model;

import java.util.List;

/**
 * The login client of the realm: the client through which the users of every organisation log in
 * to the applications of the realm owner. Its clientId is the realm name.
 *
 * @param issuer                 the "iss" of the tokens, for the applications of the realm owner. Their OpenID
 *                               discovery document is at issuer + /.well-known/openid-configuration
 * @param consoleUrl             the Keycloak admin console of the realm, where the realm owner logs in with their own
 *                               account. Null outside of ON_PREM mode
 * @param redirectUris           where Keycloak may send the user back after login
 * @param postLogoutRedirectUris where Keycloak may send the user back after logout
 * @param webOrigins             origins allowed to call Keycloak from a browser (CORS), "+" = the origins of the redirect URIs
 * @param defaultCatalogs        catalogs in every token of the client, sorted by name. The other catalogs are only in
 *                               a token when the application asks for them (scope=&lt;catalog&gt;)
 * @param lightweightTokens      the client issues lightweight access tokens (see {@link LightweightTokens}): the APIs of
 *                               the realm owner read the roles and the catalogs by introspection
 */
public record LoginClient(
    String clientId,
    String issuer,
    String consoleUrl,
    List<String> redirectUris,
    List<String> postLogoutRedirectUris,
    List<String> webOrigins,
    List<String> defaultCatalogs,
    boolean lightweightTokens){ }
