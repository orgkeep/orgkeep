package io.orgkeep.api.model;

import java.util.List;

/**
 * The client to call the API of Orgkeep with, from the screens an integrator builds on the core (the core alone,
 * without the dashboard). Orgkeep only reads the realm roles and the roles of the orgs: its tokens carry no catalog,
 * and are never lightweight. The login client (clientId = realm name) stays for the applications of the realm owner,
 * whose tokens carry the catalogs and may be lightweight.
 * <p>
 * Public client, authorization code flow with PKCE, created without redirect URI: the realm owner sets those of the
 * screens of the integrator. For the user, a single login: the token of this client comes without a new login,
 * thanks to the Keycloak session of the login client.
 *
 * @param issuer                 the "iss" of the tokens, the same as for the login client
 * @param redirectUris           where Keycloak may send the user back after login
 * @param postLogoutRedirectUris where Keycloak may send the user back after logout
 * @param webOrigins             origins allowed to call Keycloak from a browser (CORS), "+" = the origins of the redirect URIs
 */
public record OrgkeepApiClient(
    String clientId,
    String issuer,
    List<String> redirectUris,
    List<String> postLogoutRedirectUris,
    List<String> webOrigins){

    /** Visible in the login URL of the screens of the integrator: accepted for now, the white label comes later. */
    public static final String CLIENT_ID = "orgkeep-api";
}
