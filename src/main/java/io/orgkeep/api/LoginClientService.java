package io.orgkeep.api;

import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;

/**
 * Settings of the login client of the realm (see {@link LoginClient}), managed by the realm owner
 * without going through the Keycloak console.
 */
public interface LoginClientService {
    /** @throws io.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient get(String realm);

    /** @throws io.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient update(String realm, UpdateLoginClientRequest request);
}
