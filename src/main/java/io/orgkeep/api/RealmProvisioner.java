package io.orgkeep.api;

import io.orgkeep.api.model.CreateRealmRequest;
import io.orgkeep.api.model.CreateRealmResponse;
import io.orgkeep.api.model.SmtpSettings;

public interface RealmProvisioner {
    /**
     * Creates the realm with its roles, its owner and the default catalog, then {@link #ensureDefaults}.
     * Without adminPassword, the owner gets a random password and the invitation email (see {@link #configureSmtp}).
     */
    CreateRealmResponse create(CreateRealmRequest request);

    /**
     * Sets the SMTP server Keycloak uses for the emails of the realm, replacing the previous one.
     * Without it, users created without password receive no invitation.
     */
    void configureSmtp(String name, SmtpSettings smtp);

    /**
     * Sets the theme of the login pages of the realm. The theme must be deployed in Keycloak: an unknown theme is
     * not refused, Keycloak falls back to its default one.
     */
    void setLoginTheme(String name, String theme);

    /**
     * Creates what Orgkeep expects in the realm when it is missing: the login client of the realm owner's
     * applications, whose clientId is the realm name. Not the default catalog, which the realm owner may delete.
     * Idempotent: also run at each startup on an existing realm.
     */
    void ensureDefaults(String name);

    /**
     * Makes the realm role realm-owner a composite of the client role realm-management/realm-admin: the realm owners
     * can then manage their realm in the Keycloak admin console, confined to it. For ON_PREM, where the realm owner
     * runs their own Keycloak; not in SAAS. Idempotent.
     */
    void grantConsoleAccess(String name);

    void delete(String name);
    boolean exists(String name);
}
