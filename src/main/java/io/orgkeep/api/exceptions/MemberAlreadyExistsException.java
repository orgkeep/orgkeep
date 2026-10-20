package io.orgkeep.api.exceptions;

public class MemberAlreadyExistsException extends AlreadyExistsException{
    public MemberAlreadyExistsException(String orgName, String email) {
        super("User '%s' is already a member of the organisation '%s'".formatted(email,orgName));
    }
}
