package io.orgkeep.api.exceptions;

/** Products and catalog roles share the role names of the catalog client. */
public class RoleNameTakenException extends AlreadyExistsException{
    public RoleNameTakenException(String catalog, String name) {
        super("A product or a catalog role named '%s' already exists in the catalog '%s'".formatted(name, catalog));
    }
}
