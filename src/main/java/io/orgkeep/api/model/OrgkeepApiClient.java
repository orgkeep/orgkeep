package io.orgkeep.api.model;

/**
 * The client to call the API of Orgkeep with, from the screens an integrator builds on the core (the core alone,
 * without the dashboard). Orgkeep only reads the realm roles and the roles of the orgs: its tokens carry no catalog,
 * and are never lightweight. The login client (clientId = realm name) stays for the applications of the realm owner,
 * whose tokens carry the catalogs and may be lightweight.
 * <p>
 * Public client, authorization code flow with PKCE, created without redirect URI: the integrator sets those of its
 * screens. For the user, a single login: the token of this client comes without a new login, thanks to the Keycloak
 * session of the login client.
 */
public final class OrgkeepApiClient {
    /** Visible in the login URL of the screens of the integrator: accepted for now, the white label comes later. */
    public static final String CLIENT_ID = "orgkeep-api";

    private OrgkeepApiClient() {
    }
}
