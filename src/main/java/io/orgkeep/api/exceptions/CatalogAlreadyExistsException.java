package io.orgkeep.api.exceptions;

public class CatalogAlreadyExistsException extends AlreadyExistsException{
    public CatalogAlreadyExistsException(String realmName, String name) {
        super("The name '%s' is already taken by a client or a client scope of the realm '%s'".formatted(name,realmName));
    }
}
