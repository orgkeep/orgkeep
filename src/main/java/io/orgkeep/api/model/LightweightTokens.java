package io.orgkeep.api.model;

/**
 * Keycloak conventions of the lightweight access tokens.
 * <p>
 * A client in lightweight mode issues access tokens without their content (roles, catalogs, profile): the APIs read
 * it back by introspection (RFC 7662). The lightweight mode itself is native to Keycloak, the marker is a convention
 * of Orgkeep: every lightweight token carries the claim {@value #MARKER_CLAIM}: true, so that an API receiving both
 * kinds of tokens introspects only the lightweight ones. The marker comes from a client scope of the same name,
 * holding a native "Hardcoded claim" mapper, a default scope of every client in lightweight mode.
 */
public final class LightweightTokens {
    /** Client attribute of Keycloak turning the lightweight access tokens on. */
    public static final String CLIENT_ATTRIBUTE = "client.use.lightweight.access.token.enabled";

    /** The marker claim, also the name of its client scope: neutral, like every name visible in a token. */
    public static final String MARKER_CLAIM = "lightweight";

    /**
     * Client attribute of Keycloak ("Support JWT claim in Introspection Response"), on the client that introspects:
     * with "Accept: application/jwt" on the introspection, the answer gets a "jwt" field, the full access token signed
     * by Keycloak. For a gateway that introspects the lightweight tokens and passes full ones to the APIs behind it
     * (phantom token). Set on every catalog client: without the header, nothing changes.
     */
    public static final String INTROSPECTION_JWT_ATTRIBUTE = "client.introspection.response.allow.jwt.claim.enabled";

    private LightweightTokens() {
    }
}
