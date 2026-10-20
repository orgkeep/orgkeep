package io.orgkeep.api.exceptions;

public class ProductNotAssignedException extends ResourceNotFoundException{
    public ProductNotAssignedException(String orgName, String catalog, String product) {
        super("Product '%s' of the catalog '%s' is not assigned to the organisation '%s'".formatted(product, catalog, orgName));
    }
}
