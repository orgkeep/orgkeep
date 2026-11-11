package org.orgkeep.web.openapi;

import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakClients;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.List;

/**
 * Creates the Keycloak client used by the Swagger UI login (see SwaggerLoginFilter), when orgkeep.swagger-ui.login
 * is true (dev mode).
 * <p>
 * Swagger UI calls the Keycloak token endpoint from the browser, from another origin (localhost:8282 to
 * localhost:8280): Keycloak only answers with CORS headers for a client whose web origins allow it,
 * which admin-cli does not. Hence a dedicated public client, allowed from any origin: dev only.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class SwaggerClient {
    // also set as quarkus.swagger-ui.oauth-client-id, to prefill the login dialog
    static final String CLIENT_ID = "swagger-ui";

    private final OrgkeepConfig config;
    private final KeycloakClients clients;

    // after OnPremiseBootstrap (default priority APPLICATION + 500), once the realm exists
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent ev) {
        if (!config.swaggerUi().login()) {
            return;
        }
        String realm = config.realmName()
                .orElseThrow(() -> new IllegalStateException("orgkeep.realm-name is required by the Swagger UI login"));

        if (clients.create(realm, swaggerClient()).isPresent()) {
            log.info("Client '{}' created in realm '{}' for the Swagger UI login", CLIENT_ID, realm);
        }
    }

    private static ClientRepresentation swaggerClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(CLIENT_ID);
        client.setName("Swagger UI (dev)");
        client.setPublicClient(true);
        // password grant only: no redirect-based login
        client.setStandardFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(true);
        client.setWebOrigins(List.of("*"));
        return client;
    }
}
