package org.orgkeep.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;
import org.orgkeep.components.realm.BootstrapCredentials;

import java.util.Optional;

/**
 * KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD: an admin of the master realm, only for the first startup. Read from
 * the environment (or the profile of dev and tests), no orgkeep.* property: the less they look like configuration, the
 * sooner they are removed.
 */
@ApplicationScoped
class BootstrapCredentialsSource {

    /** Present when both are set and not blank. */
    Optional<BootstrapCredentials> read() {
        Optional<String> user = value("KEYCLOAK_ADMIN_USER");
        Optional<String> password = value("KEYCLOAK_ADMIN_PASSWORD");
        if (user.isPresent() && password.isPresent()) {
            return Optional.of(new BootstrapCredentials(user.get(), password.get()));
        }
        return Optional.empty();
    }

    private static Optional<String> value(String name) {
        return ConfigProvider.getConfig().getOptionalValue(name, String.class).filter(v -> !v.isBlank());
    }
}
