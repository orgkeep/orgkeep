package org.orgkeep.api.exceptions;

public class FeatureNotFoundException extends ResourceNotFoundException{
    public FeatureNotFoundException(String catalog, String product, String feature) {
        super("Product '%s' of the catalog '%s' has no feature '%s'".formatted(product, catalog, feature));
    }
}
