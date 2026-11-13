package org.orgkeep.api.exceptions;

/** The product was assigned to the organisation by another call, between the check and the creation of its group. */
public class ProductAlreadyAssignedException extends AlreadyExistsException {
    public ProductAlreadyAssignedException(String orgName, String catalog, String product) {
        super("Product '%s' of the catalog '%s' has just been assigned to the organisation '%s'".formatted(product, catalog, orgName));
    }
}
