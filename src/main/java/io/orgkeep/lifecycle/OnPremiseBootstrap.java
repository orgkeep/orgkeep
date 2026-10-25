package io.orgkeep.lifecycle;

import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.model.CreateRealmRequest;
import io.orgkeep.api.model.SmtpSettings;
import io.orgkeep.config.OrgkeepConfig;
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

    void onStart(@Observes StartupEvent ev) {
        if (config.mode() != OrgkeepConfig.Mode.ON_PREM) {
            log.info("Mode {}: no bootstrap", config.mode());
            return;
        }

        String realmName = required(config.realmName(), "orgkeep.realm-name", "ORGKEEP_REALM_NAME");
        String ownerEmail = required(config.owner().email(), "orgkeep.owner.email", "ORGKEEP_OWNER_EMAIL");
        String ownerPassword = required(config.owner().password(), "orgkeep.owner.password", "ORGKEEP_OWNER_PASSWORD");

        if (realmService.exists(realmName)) {
            // a realm created by an older version may lack what was added since
            realmService.ensureDefaults(realmName);
            log.info("Realm '{}' already exists, defaults checked", realmName);
        } else {
            // only checked when the owner is created: Keycloak asks for them at login otherwise
            String firstName = required(config.owner().firstName(), "orgkeep.owner.first-name", "ORGKEEP_OWNER_FIRST_NAME");
            String lastName = required(config.owner().lastName(), "orgkeep.owner.last-name", "ORGKEEP_OWNER_LAST_NAME");
            CreateRealmRequest request = new CreateRealmRequest(realmName, ownerEmail, ownerPassword,
                    config.owner().username().orElse(null), config.owner().temporaryPassword(), firstName, lastName);
            realmService.create(request);
            log.info("Realm '{}' created for owner {}", realmName, ownerEmail);
        }
        // at each startup: also repairs a realm created by an older version
        realmService.grantConsoleAccess(realmName);

        // the configuration wins at each startup, a change made in the Keycloak console is overwritten
        config.smtp().ifPresent(smtp -> realmService.configureSmtp(realmName, toSmtpSettings(smtp)));
        config.loginTheme().ifPresent(theme -> realmService.setLoginTheme(realmName, theme));
    }

    private static SmtpSettings toSmtpSettings(OrgkeepConfig.Smtp smtp) {
        return new SmtpSettings(smtp.host(), smtp.port(), smtp.from(), smtp.fromDisplayName().orElse(null),
                smtp.username().orElse(null), smtp.password().orElse(null), smtp.starttls(), smtp.ssl());
    }

    private static String required(Optional<String> value, String property, String envVar) {
        return value.filter(v -> !v.isBlank()).orElseThrow(() ->
                new IllegalStateException("%s (%s) is required in ON_PREM mode".formatted(property, envVar)));
    }
}
