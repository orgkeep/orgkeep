package io.orgkeep.dashboard.auth;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Map;

/**
 * CSRF protection of the API when it is called with the session cookie.
 * <p>
 * The browser sends the cookie with every request to this site, including one triggered by another site (a form
 * posted to /api/admin/organisations/acme, an image...). Such a request cannot carry a custom header: a page of
 * another origin needs a CORS preflight for that, and the API allows no other origin. So the API refuses a request
 * authenticated by the session cookie unless it carries X-Requested-With: JavaScript, which every fetch of the
 * front sends. Spring Security's CsrfFilter does the same job with a token instead of a header.
 * <p>
 * Bearer requests are not concerned: a browser never adds an Authorization header on its own.
 * /api/auth/* is left out: login and logout are browser navigations, which cannot carry a header.
 * <p>
 * preMatching: runs before JAX-RS looks for the endpoint, so an unknown path is refused the same way. The session is
 * already checked at that point (proactive authentication, before JAX-RS), only the header is missing.
 */
public class CsrfFilter {

    @ServerRequestFilter(preMatching = true)
    public Response requireCsrfHeader(ContainerRequestContext request) {
        String path = request.getUriInfo().getRequestUri().getPath();
        boolean usesSession = DashboardSession.usesSession(request.getHeaderString(HttpHeaders.AUTHORIZATION),
                request.getCookies().keySet());
        if (!usesSession || DashboardSession.isAuthPath(path)
                || DashboardSession.CSRF_HEADER_VALUE.equals(request.getHeaderString(DashboardSession.CSRF_HEADER))) {
            // null: go on with the request
            return null;
        }
        // same body as every error of the API, see ExceptionMappers in orgkeep-core
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", "Missing header " + DashboardSession.CSRF_HEADER + ": "
                        + DashboardSession.CSRF_HEADER_VALUE))
                .build();
    }
}
