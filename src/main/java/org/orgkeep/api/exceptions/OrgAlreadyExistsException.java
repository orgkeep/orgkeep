package org.orgkeep.api.exceptions;

public class OrgAlreadyExistsException extends AlreadyExistsException{
    public OrgAlreadyExistsException(String realmName, String orgName) {
        super("An organisation with name '%s' already exists in the realm '%s'".formatted(orgName,realmName));
    }
}
