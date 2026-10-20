package io.orgkeep.api.exceptions;

public class UserGroupAlreadyExistsException extends AlreadyExistsException{
    public UserGroupAlreadyExistsException(String orgName, String groupName) {
        super("A user group '%s' already exists in the organisation '%s'".formatted(groupName,orgName));
    }
}
