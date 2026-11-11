package org.orgkeep.api;

import org.orgkeep.api.model.LoginClient;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.api.model.UpdateOrgkeepApiClientRequest;

/**
 * Settings of the login client of the realm (see {@link LoginClient}), managed by the realm owner
 * without going through the Keycloak console.
 */
public interface LoginClientService {
    /** @throws org.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient get(String realm);

    /** @throws org.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client */
    LoginClient update(String realm, UpdateLoginClientRequest request);

    /**
     * The client of the API of Orgkeep (see {@link OrgkeepApiClient}), the login client of the screens of an integrator.
     *
     * @throws org.orgkeep.api.exceptions.OrgkeepApiClientNotFoundException if the realm has no such client
     */
    OrgkeepApiClient getOrgkeepApiClient(String realm);

    /** @throws org.orgkeep.api.exceptions.OrgkeepApiClientNotFoundException if the realm has no such client */
    OrgkeepApiClient updateOrgkeepApiClient(String realm, UpdateOrgkeepApiClientRequest request);
}
