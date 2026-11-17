package org.orgkeep.dashboard.auth;

import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.oidc.OIDCException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.orgkeep.api.DashboardClient;
import org.orgkeep.api.model.DashboardClientCredentials;

import java.util.Map;

/**
 * The client secret of the OIDC tenant "dashboard" (quarkus.oidc.dashboard.credentials.client-secret.provider.*).
 * Nobody configures it: Keycloak generates it when Orgkeep creates the client, and {@link DashboardClient} reads it.
 * <p>
 * This is the one documented exception to "the dashboard never calls core services": browser authentication code,
 * no business rule, no Keycloak access of its own, through the published package org.orgkeep.api.
 * <p>
 * When the client is not in the realm yet (a login that comes before OnPremiseBootstrap created it), the real reason
 * is logged and an OIDCException is thrown: Quarkus then treats the tenant as not ready and tries again at the next
 * request. It never returns an empty map (the tenant would be built without a secret) and never logs the secret.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
@Named("orgkeep-dashboard")
public class DashboardSecretProvider implements CredentialsProvider {
    /** quarkus.oidc.dashboard.credentials.client-secret.provider.key */
    static final String KEY = "secret";

    private final DashboardClient dashboardClient;

    @Override
    public Map<String, String> getCredentials(String credentialsProviderName) {
        try {
            DashboardClientCredentials credentials = dashboardClient.credentials().orElseThrow(() ->
                    new IllegalStateException("The dashboard needs orgkeep.dashboard.url (ORGKEEP_DASHBOARD_URL): "
                            + "without it Orgkeep creates no dashboard client"));
            return Map.of(KEY, credentials.secret());
        } catch (RuntimeException e) {
            // the message of the cause names what is wrong (ORGKEEP_DASHBOARD_URL, a missing client): never a secret
            String message = "Dashboard client secret not available: " + e.getMessage();
            log.warn("{}, the login will be tried again at the next request", message);
            throw new OIDCException(message, e);
        }
    }
}
