package org.orgkeep.dashboard.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import lombok.RequiredArgsConstructor;
import org.orgkeep.config.OrgkeepConfig;

/**
 * The dashboard image refuses to start without its public URL: from it the core creates the Keycloak client of the
 * browser login. %prod.orgkeep.dashboard.url=${ORGKEEP_DASHBOARD_URL} alone would not do it: for an Optional property,
 * Quarkus reads an unresolved variable as absent, and the failure would only show at the first login.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class DashboardUrlCheck {
    private final OrgkeepConfig config;

    // before OnPremiseBootstrap (APPLICATION + 500): the message comes first, whatever Keycloak does
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
        if (config.dashboard().url().filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalStateException(
                    "orgkeep.dashboard.url (ORGKEEP_DASHBOARD_URL) is required: the public URL of the dashboard, "
                            + "e.g. https://dashboard.example.com. Orgkeep creates the Keycloak client of the "
                            + "dashboard from it");
        }
    }
}
