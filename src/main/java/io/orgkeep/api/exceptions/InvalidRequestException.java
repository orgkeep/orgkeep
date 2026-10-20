package io.orgkeep.api.exceptions;

/**
 * The request is well-formed but invalid against the current state of the realm (e.g. a catalog that does not exist
 * in a list of catalogs). Checks Bean Validation cannot do, as they need to read Keycloak.
 */
public class InvalidRequestException extends RuntimeException{
    public InvalidRequestException(String message){
        super(message);
    }
}
