package io.orgkeep.dashboard.auth;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;

/**
 * Login entry point of the front: when nobody is logged in, the front navigates the browser here at once (a full
 * page load, not a fetch).
 * <p>
 * An anonymous call never reaches the method: @Authenticated fails, and the OIDC web-app mechanism answers with a
 * redirect to the Keycloak login page. Keycloak then sends the browser back to this same path with an authorization
 * code, Quarkus exchanges it for the tokens and creates the session cookie, and this time the method runs and
 * sends the browser back to the front. Close to Spring Security's oauth2Login(), minus the configuration class.
 * <p>
 * Logout has no endpoint: Quarkus intercepts /api/auth/logout itself (quarkus.oidc.logout.path).
 */
@Path("/auth")
public class AuthResource {

    @GET
    @Path("/login")
    @Authenticated
    public Response login(@Context UriInfo uriInfo) {
        // Not URI.create("/"): JAX-RS resolves a relative Location against the application base URI (/api/),
        // which would send the browser to /api/. Build an absolute URI on the root of the server instead.
        URI front = uriInfo.getBaseUriBuilder().replacePath("/").build();
        return Response.seeOther(front).build();
    }
}
