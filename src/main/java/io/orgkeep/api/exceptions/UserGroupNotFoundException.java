package io.orgkeep.api.exceptions;

public class UserGroupNotFoundException extends ResourceNotFoundException{
    public UserGroupNotFoundException(String orgName, String groupName) {
        super("No user group '%s' in the organisation '%s'".formatted(groupName,orgName));
    }
}
