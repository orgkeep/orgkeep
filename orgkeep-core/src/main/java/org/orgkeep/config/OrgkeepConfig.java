package org.orgkeep.config;

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

    /** The client of the managed realm whose service account Orgkeep uses to call the Keycloak admin API (ON_PREM). */
    Keycloak keycloak();

    Owner owner();

    Demo demo();

    SwaggerUi swaggerUi();

    /**
     * SMTP server of the realm, set at each startup in ON_PREM mode. Without a host, the realm keeps its own settings.
     * Not an Optional group: its defaults (port, starttls, ssl) would make SmallRye consider it always present.
     */
    Smtp smtp();

    Dashboard dashboard();

    Invitation invitation();

    Locks locks();

    enum Mode { ON_PREM, SAAS }

    interface Keycloak {
        /** Client of the managed realm, with the role realm-management/realm-admin on its service account. */
        @WithDefault("orgkeep-admin")
        String clientId();

        /** Required in ON_PREM. Given to the client when Orgkeep creates the realm. */
        Optional<String> clientSecret();
    }

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
        /** Absent: Orgkeep leaves the SMTP settings of the realm alone. */
        Optional<String> host();

        @WithDefault("25")
        int port();

        /** Sender address, required by Keycloak: checked at startup when the host is set. */
        Optional<String> from();

        Optional<String> fromDisplayName();

        /** Authentication is enabled when set. */
        Optional<String> username();
        Optional<String> password();

        @WithDefault("false")
        boolean starttls();

        @WithDefault("false")
        boolean ssl();
    }

    /** The Keycloak client of orgkeep-dashboard, created and repaired by Orgkeep when url is set (ON_PREM). */
    interface Dashboard {
        /** Default orgkeep-dashboard. Also the client id of the dashboard's OIDC tenant. */
        @WithDefault("orgkeep-dashboard")
        String clientId();

        /**
         * Public URL of the dashboard, an origin (https://dashboard.example.com, http on localhost only).
         * Set: the client is created or repaired at each startup, redirect &lt;url&gt;/*. Absent: no dashboard,
         * nothing done.
         */
        Optional<String> url();

        /**
         * Login theme of the dashboard client only (e.g. orgkeep, see the orgkeep-theme module), set at each startup
         * in ON_PREM mode. The other clients keep the theme of the realm, Keycloak's by default (white label: the
         * login page of the users of the orgs does not reveal Orgkeep). Absent: the client keeps its own.
         */
        Optional<String> loginTheme();
    }

    interface Invitation {
        /** Validity of the link of the invitation email. */
        @WithDefault("72h")
        Duration linkLifespan();
    }

    interface Locks {
        /**
         * How long a short write waits for a resource held by another change before the call is refused with a 409.
         * Heavy operations on an organisation (enable, disable, delete) do not wait at all.
         */
        @WithDefault("2s")
        Duration maxWait();
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
