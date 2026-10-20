package io.orgkeep.api.exceptions;

public class UserNotFoundException extends ResourceNotFoundException{
    public UserNotFoundException(String realmName, String userId) {
        super("No user '%s' in the realm '%s'".formatted(userId, realmName));
    }
}
