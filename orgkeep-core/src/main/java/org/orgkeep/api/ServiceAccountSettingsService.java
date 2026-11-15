package org.orgkeep.api;

import org.orgkeep.api.model.ServiceAccountSettings;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;

/**
 * Settings of the service accounts, for the realm owner: the mode of the tokens of one service account, and the default
 * of the service accounts created afterwards. The org admins never read nor change them.
 */
public interface ServiceAccountSettingsService {
    /** The default of the service accounts created afterwards: false when never set. */
    ServiceAccountSettings realmDefaults(String realm);

    /** Only the default changes: the existing service accounts do not. */
    ServiceAccountSettings updateRealmDefaults(String realm, UpdateServiceAccountSettingsRequest request);

    /** @throws org.orgkeep.api.exceptions.OrgNotFoundException if the organisation is unknown (404 too for an unknown service account) */
    ServiceAccountSettings get(String realm, String orgName, String name);

    /** Always writes (idempotent): repeating the call also repairs a client edited in the console. */
    ServiceAccountSettings update(String realm, String orgName, String name, UpdateServiceAccountSettingsRequest request);
}
