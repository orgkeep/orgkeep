package io.orgkeep.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "orgkeep")
public interface OrgkeepConfig {
    @WithDefault("on-prem")
    Mode mode();

    Optional<String> realmName();

    /**
     * Keycloak as the browsers see it (e.g. https://login.example.com), shown to the realm owner: issuer of the
     * tokens, admin console. Defaults to the base of quarkus.oidc.auth-server-url, an internal URL in Docker.
     */
    Optional<String> keycloakPublicUrl();

    Owner owner();

    Demo demo();

    SwaggerUi swaggerUi();

    /** SMTP server of the realm, set at each startup in ON_PREM mode. Absent: the realm keeps its own settings. */
    Optional<Smtp> smtp();

    /**
     * Login theme of the realm (e.g. orgkeep, see the orgkeep-theme module), set at each startup in ON_PREM mode.
     * Absent: the realm keeps its own, Keycloak's by default (white label: nothing on the login page reveals Orgkeep).
     */
    Optional<String> loginTheme();

    Invitation invitation();

    enum Mode { ON_PREM, SAAS }

    interface Owner {
        Optional<String> email();
        Optional<String> password();

        /** Defaults to the email. */
        Optional<String> username();

        /** Required when the realm is created (ON_PREM): Keycloak asks for them at login otherwise. */
        Optional<String> firstName();
        Optional<String> lastName();

        /** When true (default), the owner must change the password at first login. */
        @WithDefault("true")
        boolean temporaryPassword();
    }

    interface Demo {
        /** Seeds demo data at startup (see DemoSeeder). Enabled in dev mode, or by ORGKEEP_DEMO_ENABLED=true. */
        @WithDefault("false")
        boolean enabled();
    }

    interface Smtp {
        String host();

        @WithDefault("25")
        int port();

        /** Sender address, required by Keycloak. */
        String from();

        Optional<String> fromDisplayName();

        /** Authentication is enabled when set. */
        Optional<String> username();
        Optional<String> password();

        @WithDefault("false")
        boolean starttls();

        @WithDefault("false")
        boolean ssl();
    }

    interface Invitation {
        /** Validity of the link of the invitation email. */
        @WithDefault("72h")
        Duration linkLifespan();
    }

    interface SwaggerUi {
        /**
         * Login with username / password in Swagger UI instead of pasting a token
         * (see SwaggerLoginFilter and SwaggerClientSeeder). Enabled in dev mode only.
         */
        @WithDefault("false")
        boolean login();

        /**
         * Realm URL as seen by the browser, for the token URL of the login. Defaults to the realm under
         * orgkeep.keycloak-public-url, else to quarkus.oidc.auth-server-url, which is fine in dev but is an internal
         * URL in Docker (http://keycloak:8080/...).
         */
        Optional<String> authServerUrl();
    }
}
