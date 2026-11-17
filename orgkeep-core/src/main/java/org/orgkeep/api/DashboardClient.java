package org.orgkeep.api;

import org.orgkeep.api.model.DashboardClientCredentials;

import java.util.Optional;

/** The Keycloak client the dashboard logs browsers in with. For the OIDC tenant of orgkeep-dashboard only. */
public interface DashboardClient {
    /**
     * Client id and secret of the dashboard client of the application realm, read from Keycloak with the admin
     * service account at the first call, then kept in memory until the application stops. Blocking.
     * Empty when orgkeep.dashboard.url is not set (the core alone).
     *
     * @throws IllegalStateException when the url is set but the client is not (yet) in the realm; not cached,
     *         so a later call retries
     */
    Optional<DashboardClientCredentials> credentials();
}
