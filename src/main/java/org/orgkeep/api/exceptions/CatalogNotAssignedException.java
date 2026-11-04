package org.orgkeep.api.exceptions;

public class CatalogNotAssignedException extends ResourceNotFoundException{
    public CatalogNotAssignedException(String orgName, String catalog) {
        super("The organisation '%s' has no product of the catalog '%s'".formatted(orgName, catalog));
    }
}
