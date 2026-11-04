package org.orgkeep.api.exceptions;

public class ClientAlreadyExistsException extends AlreadyExistsException{
    public ClientAlreadyExistsException(String realmName, String clientId) {
        super("A client with id '%s' already exists in the realm '%s'".formatted(clientId,realmName));
    }
}
