package org.orgkeep.api.exceptions;

public class CatalogRoleNotFoundException extends ResourceNotFoundException{
    public CatalogRoleNotFoundException(String catalog, String role) {
        super("No catalog role '%s' in the catalog '%s'".formatted(role, catalog));
    }
}
