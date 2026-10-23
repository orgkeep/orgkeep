package io.orgkeep.api;

import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.OrgkeepApiClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import io.orgkeep.api.model.UpdateOrgkeepApiClientRequest;

/**
 * Settings of the login client of the realm (see {@link LoginClient}), managed by the realm owner
 * without going through the Keycloak console.
 */
public interface LoginClientService {
    /** @throws io.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient get(String realm);

    /** @throws io.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient update(String realm, UpdateLoginClientRequest request);

    /**
     * The client of the API of Orgkeep (see {@link OrgkeepApiClient}), the login client of the screens of an integrator.
     *
     * @throws io.orgkeep.api.exceptions.OrgkeepApiClientNotFoundException if the realm has no such client
     */
    OrgkeepApiClient getOrgkeepApiClient(String realm);

    /** @throws io.orgkeep.api.exceptions.OrgkeepApiClientNotFoundException if the realm has no such client */
    OrgkeepApiClient updateOrgkeepApiClient(String realm, UpdateOrgkeepApiClientRequest request);
}
