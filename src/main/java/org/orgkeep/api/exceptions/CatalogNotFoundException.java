package org.orgkeep.api.exceptions;

public class CatalogNotFoundException extends ResourceNotFoundException{
    public CatalogNotFoundException(String realmName, String name) {
        super("No catalog named '%s' in the realm '%s'".formatted(name,realmName));
    }
}
