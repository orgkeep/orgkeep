package io.orgkeep.dashboard.auth;

import io.quarkus.oidc.TenantResolver;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chooses the OIDC tenant that authenticates a request, before any check: the counterpart, in Spring Security,
 * of two SecurityFilterChains (oauth2ResourceServer() and oauth2Login()) picked by a request matcher.
 * <ul>
 *     <li>an Authorization header: the default tenant, bearer only. A session cookie sent along is ignored, there
 *     is never a fallback from a refused token to the session;</li>
 *     <li>/api/auth/* (login, the return from Keycloak, logout), or a session cookie: the "dashboard" tenant;</li>
 *     <li>anything else: the default tenant. A protected endpoint then answers 401, not a redirect to Keycloak.</li>
 * </ul>
 * Quarkus calls it on every request (a single bean of this type is allowed). The core knows nothing of it:
 * on its own, the core only has the default tenant.
 */
@ApplicationScoped
public class DashboardTenantResolver implements TenantResolver {
    /**
     * The id Quarkus gives to the tenant configured by quarkus.oidc.* (without a tenant name). Returned explicitly:
     * null would mean "not resolved", and Quarkus would go on with its own rules, which pick the tenant of a session
     * cookie. A bad bearer token sent with a valid cookie would then be ignored in favour of the session.
     */
    static final String DEFAULT_TENANT = "Default";

    @Override
    public String resolve(RoutingContext context) {
        if (context.request().getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return DEFAULT_TENANT;
        }
        Set<String> cookieNames = context.request().cookies().stream().map(Cookie::getName).collect(Collectors.toSet());
        if (DashboardSession.isAuthPath(context.normalizedPath()) || DashboardSession.usesSession(null, cookieNames)) {
            return DashboardSession.TENANT;
        }
        return DEFAULT_TENANT;
    }
}
