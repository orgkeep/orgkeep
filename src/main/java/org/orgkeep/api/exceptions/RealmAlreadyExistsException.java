package org.orgkeep.api.exceptions;


public class RealmAlreadyExistsException extends AlreadyExistsException{
    public RealmAlreadyExistsException(String realmName) {
        super("A realm with name '%s already exists'".formatted(realmName));
    }
}
