package org.orgkeep.lifecycle;

import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.DashboardClientSettings;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.components.realm.AdminClient;
import org.orgkeep.components.realm.BootstrapCredentials;
import org.orgkeep.components.realm.RealmBootstrap;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess.Probe;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import java.util.Optional;

@Slf4j
@Dependent
public class OnPremiseBootstrap {
    @Inject
    OrgkeepConfig config;

    @Inject
    RealmProvisioner realmService;

    @Inject
    RealmBootstrap realmBootstrap;

    @Inject
    KeycloakAdminAccess adminAccess;

    @Inject
    BootstrapCredentialsSource bootstrapCredentials;

    void onStart(@Observes StartupEvent ev) {
        if (config.mode() != OrgkeepConfig.Mode.ON_PREM) {
            log.info("Mode {}: no bootstrap", config.mode());
            return;
        }

        String realmName = required(config.realmName(), "orgkeep.realm-name", "ORGKEEP_REALM_NAME");
        String ownerEmail = required(config.owner().email(), "orgkeep.owner.email", "ORGKEEP_OWNER_EMAIL");
        String clientId = config.keycloak().clientId();
        String clientSecret = required(config.keycloak().clientSecret(), "orgkeep.keycloak.client-secret",
                "ORGKEEP_KEYCLOAK_CLIENT_SECRET");
        // checked before any call to Keycloak, like the settings above
        Optional<String> dashboardUrl = config.dashboard().url().map(OnPremiseBootstrap::checkedDashboardUrl);
        Optional<SmtpSettings> smtpSettings = smtpSettings(config.smtp());
        Optional<BootstrapCredentials> bootstrap = bootstrapCredentials.read();

        try {
            // Orgkeep first asks for a token of its own admin client: master is never contacted on a normal boot
            Probe probe = adminAccess.probe();
            if (probe == Probe.UNAUTHORIZED) {
                throw refusedClient(realmName, clientId);
            }
            boolean absent = probe == Probe.REALM_NOT_FOUND || !realmService.exists(realmName);

            if (absent) {
                BootstrapCredentials credentials = bootstrap.orElseThrow(() -> realmMissing(realmName, clientId));
                // only checked when the owner is created: Keycloak asks for them at login otherwise
                String ownerPassword = required(config.owner().password(), "orgkeep.owner.password", "ORGKEEP_OWNER_PASSWORD");
                String firstName = required(config.owner().firstName(), "orgkeep.owner.first-name", "ORGKEEP_OWNER_FIRST_NAME");
                String lastName = required(config.owner().lastName(), "orgkeep.owner.last-name", "ORGKEEP_OWNER_LAST_NAME");
                CreateRealmRequest request = new CreateRealmRequest(realmName, ownerEmail, ownerPassword,
                        config.owner().username().orElse(null), config.owner().temporaryPassword(), firstName, lastName);
                realmBootstrap.createWithBootstrapAdmin(request, new AdminClient(clientId, clientSecret), credentials);
                if (probe != Probe.OK) {
                    // only a safety net: completeCreation already ran as the service account, so it worked.
                    // The admin client now points at a realm that exists
                    Probe second = adminAccess.probe();
                    if (second == Probe.UNAUTHORIZED) {
                        throw refusedClient(realmName, clientId);
                    }
                    if (second != Probe.OK) {
                        throw realmMissing(realmName, clientId);
                    }
                }
            } else {
                if (bootstrap.isPresent()) {
                    log.warn("Realm '{}' exists: KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD are not used, "
                            + "remove them from the environment", realmName);
                }
                // a realm created by an older version may lack what was added since
                realmService.ensureDefaults(realmName);
                log.info("Realm '{}' already exists, defaults checked", realmName);
                // the configuration names the realm owner, and only them
                realmService.enforceSingleOwner(realmName, config.owner().username(), ownerEmail);
            }
            // at each startup: also repairs a realm created by an older version
            realmService.grantConsoleAccess(realmName);

            // the configuration wins at each startup, a change made in the Keycloak console is overwritten
            smtpSettings.ifPresent(settings -> realmService.configureSmtp(realmName, settings));
            dashboardUrl.ifPresentOrElse(
                    url -> realmService.ensureDashboardClient(realmName, new DashboardClientSettings(
                            config.dashboard().clientId(), url, config.dashboard().loginTheme().orElse(null))),
                    () -> config.dashboard().loginTheme().ifPresent(theme -> setDashboardLoginTheme(realmName, theme)));
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == Response.Status.FORBIDDEN.getStatusCode()) {
                throw new IllegalStateException(("The client '%s' of realm '%s' lacks rights on the admin API: its service "
                        + "account needs the role realm-management/realm-admin, and the client must have Full scope "
                        + "allowed on (Clients > %s > Client scopes > Dedicated scope).")
                        .formatted(clientId, realmName, clientId), e);
            }
            if (status == Response.Status.UNAUTHORIZED.getStatusCode()) {
                throw refusedClient(realmName, clientId);
            }
            throw e;
        }
    }

    private IllegalStateException realmMissing(String realmName, String clientId) {
        return new IllegalStateException(("Realm '%s' not found at %s. For the first startup, set KEYCLOAK_ADMIN_USER and "
                + "KEYCLOAK_ADMIN_PASSWORD (an admin of the master realm) so that Orgkeep creates the realm and its "
                + "client '%s'; remove them afterwards. Also check KEYCLOAK_URL and ORGKEEP_REALM_NAME.")
                .formatted(realmName, adminAccess.serverUrl(), clientId));
    }

    private static IllegalStateException refusedClient(String realmName, String clientId) {
        return new IllegalStateException(("Keycloak refused the client '%s' of realm '%s' (ORGKEEP_KEYCLOAK_CLIENT_ID / "
                + "ORGKEEP_KEYCLOAK_CLIENT_SECRET): wrong secret, or the client was deleted or disabled. Check "
                + "Clients > %s > Credentials in the Keycloak console of the realm; its events (failed CLIENT_LOGIN) "
                + "tell which one. A deleted client must be created again: confidential, service account enabled, "
                + "Full scope allowed on, service account role realm-management/realm-admin.")
                .formatted(clientId, realmName, clientId));
    }

    private void setDashboardLoginTheme(String realmName, String theme) {
        String clientId = config.dashboard().clientId();
        if (!realmService.setLoginTheme(realmName, clientId, theme)) {
            // no orgkeep.dashboard.url: Orgkeep does not create the client, the operator may create it later
            log.info("No client '{}' in realm '{}' yet: login theme '{}' set at the next startup", clientId, realmName, theme);
        }
    }

    /** The URL without its trailing slash, when it is an origin the dashboard client may redirect to. */
    private static String checkedDashboardUrl(String url) {
        String origin = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (!origin.matches(UpdateLoginClientRequest.ORIGIN)) {
            throw new IllegalStateException(("orgkeep.dashboard.url (ORGKEEP_DASHBOARD_URL) must be an origin: https://host[:port], "
                    + "or http://localhost[:port] for development, without path, user info or wildcard (got '%s')")
                    .formatted(url));
        }
        return origin;
    }

    /** Empty without a host (the realm keeps its settings); a host without a sender address is refused. */
    private static Optional<SmtpSettings> smtpSettings(OrgkeepConfig.Smtp smtp) {
        return smtp.host().filter(h -> !h.isBlank()).map(host -> {
            String from = smtp.from().filter(f -> !f.isBlank()).orElseThrow(() -> new IllegalStateException(
                    "orgkeep.smtp.from (ORGKEEP_SMTP_FROM) is required when orgkeep.smtp.host (ORGKEEP_SMTP_HOST) is set"));
            return new SmtpSettings(host, smtp.port(), from, smtp.fromDisplayName().orElse(null),
                    smtp.username().orElse(null), smtp.password().orElse(null), smtp.starttls(), smtp.ssl());
        });
    }

    private static String required(Optional<String> value, String property, String envVar) {
        return value.filter(v -> !v.isBlank()).orElseThrow(() ->
                new IllegalStateException("%s (%s) is required in ON_PREM mode".formatted(property, envVar)));
    }
}
