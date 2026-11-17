package org.orgkeep.components.realm;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orgkeep.api.model.DashboardClientCredentials;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakClients;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * DashboardClientImpl with a mocked Keycloak access: what matters here is the caching, the errors and the request
 * context, not Keycloak (RealmProvisionerTest covers the creation of the client). A @QuarkusTest only because the
 * request context comes from the Arc container.
 */
@QuarkusTest
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

    // the OIDC blocking executor has no request context, and the admin client is request scoped
    @Test
    void worksOnAThreadWithoutARequestContext() throws InterruptedException {
        AtomicBoolean activeDuringTheCall = new AtomicBoolean();
        // the stub stands for the admin client: it records whether a request context is active when it is reached
        when(clients.findUuid("acme", "orgkeep-dashboard")).thenAnswer(invocation -> {
            activeDuringTheCall.set(Arc.container().requestContext().isActive());
            return Optional.of("uuid");
        });
        when(clients.secret("acme", "uuid")).thenReturn(Optional.of(SECRET));
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicBoolean activeAfterTheCall = new AtomicBoolean(true);

        Thread thread = new Thread(() -> {
            try {
                result.set(dashboardClient.credentials().orElseThrow());
            } catch (RuntimeException e) {
                result.set(e);
            }
            activeAfterTheCall.set(Arc.container().requestContext().isActive());
        });
        thread.start();
        thread.join();

        assertEquals(new DashboardClientCredentials("orgkeep-dashboard", SECRET), result.get());
        // activated for the call, and terminated afterwards
        assertTrue(activeDuringTheCall.get(), "a request context is active while Keycloak is read");
        assertFalse(activeAfterTheCall.get(), "the request context is terminated after the call");
    }

    @Test
    void theSecretIsMaskedInToString() {
        String text = new DashboardClientCredentials("orgkeep-dashboard", SECRET).toString();

        assertFalse(text.contains(SECRET), text);
        assertTrue(text.contains("orgkeep-dashboard"), text);
    }
}
