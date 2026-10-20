package io.orgkeep.dashboard.auth;

import java.util.Set;

/**
 * When a request is authenticated by the session of the dashboard rather than by a bearer token: the single rule
 * shared by {@link DashboardTenantResolver} (which tenant checks the request) and {@link CsrfFilter} (which requests
 * must carry the CSRF header). Kept in one place so that both always agree.
 */
public final class DashboardSession {
    /** The OIDC tenant of the browser login: quarkus.oidc.dashboard.* in application.properties. */
    public static final String TENANT = "dashboard";
    /** Quarkus names the session cookie of a tenant q_session_&lt;tenant&gt;, plus _chunk_N when it is split. */
    static final String SESSION_COOKIE_PREFIX = "q_session_" + TENANT;
    /** The login entry point and the logout, reached by browser navigations: they start or end the session. */
    static final String AUTH_PATH = "/api/auth/";
    /** Sent by every fetch of the front. Another site cannot send it: a custom header needs a CORS preflight. */
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_HEADER_VALUE = "JavaScript";

    private DashboardSession() {
    }

    /**
     * @param authorization the Authorization header, null when absent
     * @param cookieNames   names of the cookies of the request
     * @return true when the request is authenticated by the session cookie. A request with an Authorization header
     * never is, even with a session cookie: it is a bearer request, and its token alone decides.
     */
    static boolean usesSession(String authorization, Set<String> cookieNames) {
        return authorization == null
                && cookieNames.stream().anyMatch(name -> name.startsWith(SESSION_COOKIE_PREFIX));
    }

    static boolean isAuthPath(String path) {
        return path.startsWith(AUTH_PATH);
    }
}
