package org.orgkeep.api.exceptions;

/**
 * The request is valid but conflicts with a business rule (e.g. removing the owner of an organisation).
 */
public class OperationNotAllowedException extends RuntimeException{
    public OperationNotAllowedException(String message){
        super(message);
    }
}
