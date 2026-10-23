package io.orgkeep.api.exceptions;

import io.orgkeep.api.model.OrgkeepApiClient;

public class OrgkeepApiClientNotFoundException extends ResourceNotFoundException{
    public OrgkeepApiClientNotFoundException(String realmName) {
        super("No client '%s' in the realm '%s'".formatted(OrgkeepApiClient.CLIENT_ID, realmName));
    }
}
