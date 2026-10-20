package io.orgkeep.api.exceptions;

public class ProductRoleNotFoundException extends ResourceNotFoundException{
    public ProductRoleNotFoundException(String catalog, String product, String role) {
        super("Product '%s' of the catalog '%s' has no role '%s'".formatted(product, catalog, role));
    }
}
