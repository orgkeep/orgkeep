package io.orgkeep.api.exceptions;

public class OrgNotFoundException extends ResourceNotFoundException{
    public OrgNotFoundException(String realmName, String orgName) {
        super("No organisation named '%s' in the realm '%s'".formatted(orgName,realmName));
    }
}
