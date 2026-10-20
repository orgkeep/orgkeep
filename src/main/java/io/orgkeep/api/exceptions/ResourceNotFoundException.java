package io.orgkeep.api.exceptions;

public abstract class ResourceNotFoundException extends RuntimeException{
    public ResourceNotFoundException(String message){
        super(message);
    }
}
