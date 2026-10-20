package io.orgkeep.api.exceptions;

public class ProductNotFoundException extends ResourceNotFoundException{
    public ProductNotFoundException(String catalog, String product) {
        super("No product '%s' in the catalog '%s'".formatted(product, catalog));
    }
}
