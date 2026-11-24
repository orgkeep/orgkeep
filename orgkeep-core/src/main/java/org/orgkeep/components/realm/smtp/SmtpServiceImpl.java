package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.SmtpService;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.SmtpServer;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class SmtpServiceImpl implements SmtpService {
    private final KeycloakRealms realms;
    private final SmtpMapper smtpMapper;
    private final OrgkeepConfig config;

    @Override
    public SmtpServer get(String realm) {
        return smtpMapper.toSmtpServer(realms.smtpServer(realm), managedByConfiguration());
    }

    @Override
    public SmtpServer update(String realm, UpdateSmtpRequest request) {
        checkNotManagedByConfiguration(realm);
        // read, modify, write back: the admin API replaces the whole "smtpServer" map with the one it receives
        Map<String, String> current = realms.smtpServer(realm);
        // Keycloak masks the stored password: passing the mask back makes it keep the stored password
        String keptPassword = current.get("password");
        realms.setSmtpServer(realm, smtpMapper.merge(current, smtpMapper.toSettings(request, keptPassword)));
        log.info("SMTP server {}:{} set on realm '{}' (authentication {})", request.host(), request.port(), realm,
                request.username() != null && !request.username().isBlank());
        return get(realm);
    }

    @Override
    public void delete(String realm) {
        checkNotManagedByConfiguration(realm);
        realms.setSmtpServer(realm, new HashMap<>());
        log.info("SMTP server removed from realm '{}': Keycloak sends no email", realm);
    }

    private void checkNotManagedByConfiguration(String realm) {
        if (managedByConfiguration()) {
            throw new OperationNotAllowedException(("The SMTP server of realm '%s' is set by the configuration of Orgkeep "
                    + "(orgkeep.smtp.host / ORGKEEP_SMTP_HOST): remove ORGKEEP_SMTP_HOST and the other ORGKEEP_SMTP_* variables, "
                    + "then restart Orgkeep, to manage it here. The current settings are kept.").formatted(realm));
        }
    }

    /** Same condition as OnPremiseBootstrap.smtpSettings: the configuration wins over the API in ON_PREM. */
    private boolean managedByConfiguration() {
        return config.mode() == OrgkeepConfig.Mode.ON_PREM
                && config.smtp().host().filter(host -> !host.isBlank()).isPresent();
    }
}
