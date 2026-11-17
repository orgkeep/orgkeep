package org.orgkeep.dashboard.auth;

import io.quarkus.oidc.OIDCException;
import org.junit.jupiter.api.Test;
import org.orgkeep.api.model.DashboardClientCredentials;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test, a stub of DashboardClient (a one-method interface, so a lambda): what matters is what the provider
 * answers to Quarkus. A missing client must be an OIDCException, which makes Quarkus retry the tenant at the next
 * request; an empty map would build the tenant without a secret.
 */
class DashboardSecretProviderTest {
    private static DashboardSecretProvider providerOf(java.util.function.Supplier<Optional<DashboardClientCredentials>> client) {
        return new DashboardSecretProvider(client::get);
    }

    @Test
    void givesTheSecretUnderTheConfiguredKey() {
        DashboardSecretProvider provider = providerOf(
                () -> Optional.of(new DashboardClientCredentials("orgkeep-dashboard", "the-secret")));

        assertEquals(Map.of("secret", "the-secret"), provider.getCredentials("orgkeep-dashboard"));
    }

    @Test
    void aMissingClientIsAnOidcException() {
        DashboardSecretProvider provider = providerOf(() -> {
            throw new IllegalStateException("Dashboard client 'orgkeep-dashboard' not found");
        });

        assertThrows(OIDCException.class, () -> provider.getCredentials("orgkeep-dashboard"));
    }

    @Test
    void noUrlIsAnOidcExceptionToo() {
        DashboardSecretProvider provider = providerOf(Optional::empty);

        OIDCException e = assertThrows(OIDCException.class, () -> provider.getCredentials("orgkeep-dashboard"));
        // the operator is told which variable to set
        assertTrue(e.getMessage().contains("ORGKEEP_DASHBOARD_URL"), e.getMessage());
    }
}
