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
     * Creates what Orgkeep expects in the realm when it is missing: the login client of the realm owner's
     * applications, whose clientId is the realm name. Not the default catalog, which the realm owner may delete.
     * Idempotent: also run at each startup on an existing realm.
     */
    void ensureDefaults(String name);

    void delete(String name);
    boolean exists(String name);
}
