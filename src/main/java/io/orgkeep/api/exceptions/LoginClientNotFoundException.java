package io.orgkeep.api.exceptions;

public class LoginClientNotFoundException extends ResourceNotFoundException{
    public LoginClientNotFoundException(String realmName) {
        super("No login client '%s' in the realm '%s'".formatted(realmName,realmName));
    }
}
