package org.orgkeep.api;

import org.orgkeep.api.model.TokenPreview;

import java.util.List;

/**
 * What the APIs of the realm owner receive: the claims of the access token the login client of the realm would issue
 * to a member of an organisation, for the catalogs an application asks for, and what the introspection of this token
 * returns. Computed by Keycloak without the user logging in, and never a signed token: nothing usable as a bearer
 * token leaves the core.
 * <p>
 * For the realm owner only, to check the shape of the tokens its APIs protect themselves with.
 */
public interface TokenPreviewService {

    /**
     * @param catalogs the catalogs asked for, as an application does with scope=openid &lt;catalog&gt;...
     *                 Empty: only the default catalogs of the login client
     * @return the claims of the token, and of its introspection
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a member of the organisation
     * @throws org.orgkeep.api.exceptions.InvalidRequestException for a name that is not a catalog of the realm
     * @throws org.orgkeep.api.exceptions.LoginClientNotFoundException if the realm has no login client
     */
    TokenPreview preview(String realm, String orgName, String userId, List<String> catalogs);
}
