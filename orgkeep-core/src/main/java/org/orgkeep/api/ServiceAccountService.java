package org.orgkeep.api;

import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.ServiceAccountCredentials;
import org.orgkeep.api.model.UpdateServiceAccountRequest;

import java.util.List;

/** Service accounts of an organisation: OAuth2 clients (client credentials) of its applications. */
public interface ServiceAccountService {
    /** @return the service account and its credentials, the secret is shown only here and by {@link #regenerateSecret} */
    ServiceAccountCreated create(String realm, String orgName, CreateServiceAccountRequest request);

    /** Sorted by name. */
    List<ServiceAccount> list(String realm, String orgName);

    ServiceAccount get(String realm, String orgName, String name);

    /** Replaces the description and the default catalogs. */
    ServiceAccount update(String realm, String orgName, String name, UpdateServiceAccountRequest request);

    /** The old secret stops working at once. */
    ServiceAccountCredentials regenerateSecret(String realm, String orgName, String name);

    ServiceAccount setEnabled(String realm, String orgName, String name, boolean enabled);

    /** Deletes the client, its service-account user and their group memberships. */
    void delete(String realm, String orgName, String name);
}
