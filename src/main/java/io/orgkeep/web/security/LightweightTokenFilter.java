package io.orgkeep.web.security;

import io.orgkeep.api.model.LightweightTokens;
import io.orgkeep.api.model.OrgkeepApiClient;
import io.orgkeep.web.ExceptionMappers;
import jakarta.json.JsonValue;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Refuses the lightweight tokens with an explicit 401. The login client may issue lightweight tokens (for the APIs
 * of the realm owner, which introspect them), but the API of Orgkeep reads the roles in the token: with a lightweight
 * one, every call would end in a 403 that says nothing. The API of Orgkeep is called with a token of the client
 * orgkeep-api, never lightweight.
 * <p>
 * The token is already verified when the filter runs (proactive authentication of Quarkus): it only reads the marker.
 * Pre-matching, so that it runs before the role checks of the resources (@RolesAllowed would answer 403 first).
 * The request is read through the ContainerRequestContext, never an injected request scoped bean (the filter is a
 * singleton).
 */
@Provider
@PreMatching
public class LightweightTokenFilter implements ContainerRequestFilter {
    static final String MESSAGE = "Lightweight access tokens are not accepted by the API of Orgkeep: use a token of the "
            + "client " + OrgkeepApiClient.CLIENT_ID;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // anonymous: no principal. A session of the dashboard: the ID token, never lightweight
        if (requestContext.getSecurityContext().getUserPrincipal() instanceof JsonWebToken jwt
                && isMarked(jwt.getClaim(LightweightTokens.MARKER_CLAIM))) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    // RFC 6750: the reason of a refused bearer token, for the clients that read it
                    .header(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"invalid_token\", error_description=\"" + MESSAGE + "\"")
                    .type(MediaType.APPLICATION_JSON)
                    // the body of the other errors of the API
                    .entity(new ExceptionMappers.ErrorResponse(MESSAGE))
                    .build());
        }
    }

    // the claim is a JSON value of the token (JsonValue.TRUE), or a Boolean depending on how the token was parsed
    private static boolean isMarked(Object claim) {
        return claim == JsonValue.TRUE || Boolean.TRUE.equals(claim);
    }
}
