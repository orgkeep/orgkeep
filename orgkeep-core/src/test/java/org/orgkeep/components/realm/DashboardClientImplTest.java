package org.orgkeep.components.realm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orgkeep.api.model.DashboardClientCredentials;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakClients;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DashboardClientImpl with a mocked Keycloak access: what matters here is the caching and the errors, not Keycloak
 * (RealmProvisionerTest covers the creation of the client).
 */
class DashboardClientImplTest {
    private static final String SECRET = "a-secret-of-at-least-thirty-two-characters";

    private final OrgkeepConfig config = mock(OrgkeepConfig.class);
    private final OrgkeepConfig.Dashboard dashboard = mock(OrgkeepConfig.Dashboard.class);
    private final KeycloakClients clients = mock(KeycloakClients.class);
    private final DashboardClientImpl dashboardClient = new DashboardClientImpl(config, clients);

    @BeforeEach
    void setUp() {
        when(config.dashboard()).thenReturn(dashboard);
        when(config.realmName()).thenReturn(Optional.of("acme"));
        when(dashboard.url()).thenReturn(Optional.of("https://dashboard.acme.test"));
        when(dashboard.clientId()).thenReturn("orgkeep-dashboard");
    }

    @Test
    void isEmptyWithoutTheUrl() {
        when(dashboard.url()).thenReturn(Optional.empty());

        assertTrue(dashboardClient.credentials().isEmpty());
        verifyNoInteractions(clients);
    }

    @Test
    void returnsTheClientIdAndTheSecretKeycloakHasThenCachesThem() {
        when(clients.findUuid("acme", "orgkeep-dashboard")).thenReturn(Optional.of("uuid"));
        when(clients.secret("acme", "uuid")).thenReturn(Optional.of(SECRET));

        DashboardClientCredentials first = dashboardClient.credentials().orElseThrow();
        DashboardClientCredentials second = dashboardClient.credentials().orElseThrow();

        assertEquals(new DashboardClientCredentials("orgkeep-dashboard", SECRET), first);
        assertEquals(first, second);
        verify(clients, times(1)).findUuid("acme", "orgkeep-dashboard");
    }

    @Test
    void failsWhileTheClientIsMissingAndSucceedsOnceItExists() {
        when(clients.findUuid("acme", "orgkeep-dashboard")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, dashboardClient::credentials);

        // not cached: the next call looks again
        when(clients.findUuid("acme", "orgkeep-dashboard")).thenReturn(Optional.of("uuid"));
        when(clients.secret("acme", "uuid")).thenReturn(Optional.of(SECRET));
        assertEquals(SECRET, dashboardClient.credentials().orElseThrow().secret());
    }

    @Test
    void theSecretIsMaskedInToString() {
        String text = new DashboardClientCredentials("orgkeep-dashboard", SECRET).toString();

        assertFalse(text.contains(SECRET), text);
        assertTrue(text.contains("orgkeep-dashboard"), text);
    }
}
