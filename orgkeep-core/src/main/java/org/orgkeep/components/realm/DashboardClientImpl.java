package org.orgkeep.components.realm;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.orgkeep.api.DashboardClient;
import org.orgkeep.api.model.DashboardClientCredentials;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakClients;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the secret Keycloak generated for the dashboard client, with the admin service account. Never logs it.
 * Not loaded at startup: the dashboard asks at its first browser login, so the order with OnPremiseBootstrap, which
 * creates the client, does not matter.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class DashboardClientImpl implements DashboardClient {
    private final OrgkeepConfig config;
    private final KeycloakClients clients;

    // filled by the first successful read only
    private final AtomicReference<DashboardClientCredentials> cache = new AtomicReference<>();

    @Override
    public Optional<DashboardClientCredentials> credentials() {
        if (config.dashboard().url().isEmpty()) {
            return Optional.empty();
        }
        DashboardClientCredentials cached = cache.get();
        if (cached != null) {
            return Optional.of(cached);
        }
        DashboardClientCredentials read = inRequestContext(this::read);
        cache.set(read);
        return Optional.of(read);
    }

    private DashboardClientCredentials read() {
        String realm = config.realmName()
                .orElseThrow(() -> new IllegalStateException("orgkeep.realm-name (ORGKEEP_REALM_NAME) is not set"));
        String clientId = config.dashboard().clientId();
        String uuid = clients.findUuid(realm, clientId).orElseThrow(() -> new IllegalStateException(
                "Dashboard client '%s' not found in realm '%s' (yet): Orgkeep creates it at startup".formatted(clientId, realm)));
        String secret = clients.secret(realm, uuid).orElseThrow(() -> new IllegalStateException(
                "Dashboard client '%s' of realm '%s' has no secret".formatted(clientId, realm)));
        return new DashboardClientCredentials(clientId, secret);
    }

    /**
     * The admin client is request scoped: the OIDC blocking executor that calls us has no request context, so one
     * is activated for the call when none is active.
     */
    private static <T> T inRequestContext(java.util.function.Supplier<T> call) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return call.get();
        }
        requestContext.activate();
        try {
            return call.get();
        } finally {
            requestContext.terminate();
        }
    }
}
