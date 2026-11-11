package org.orgkeep.api;

import org.orgkeep.api.model.CatalogRole;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.RoleHolder;
import org.orgkeep.api.model.UpdateCatalogRoleRequest;

import java.util.List;

/**
 * Roles valid for every product of a catalog (see {@link org.orgkeep.api.model.CatalogRoles}), defined by the realm
 * owner. The org admins grant the grantable ones through their teams; the realm owner grants any of them directly.
 * <p>
 * Every method throws {@link org.orgkeep.api.exceptions.CatalogNotFoundException} for an unknown catalog, and
 * {@link org.orgkeep.api.exceptions.CatalogRoleNotFoundException} for an unknown role.
 */
public interface CatalogRoleService {
    /** @throws org.orgkeep.api.exceptions.RoleNameTakenException if a product or a catalog role has this name */
    CatalogRole create(String realm, String catalog, CreateCatalogRoleRequest request);

    /** The catalog roles of the catalog, sorted by name. */
    List<CatalogRole> list(String realm, String catalog);

    CatalogRole get(String realm, String catalog, String role);

    CatalogRole update(String realm, String catalog, String role, UpdateCatalogRoleRequest request);

    /** Deletes the role: Keycloak removes it from the teams and the users holding it. */
    void delete(String realm, String catalog, String role);

    /**
     * Grants the role directly to the user, idempotent.
     *
     * @throws org.orgkeep.api.exceptions.UserNotFoundException if the user does not exist
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the user is not a member of an organisation
     */
    void grant(String realm, String catalog, String role, String userId);

    /** Revokes the role granted directly to the user, idempotent. */
    void revoke(String realm, String catalog, String role, String userId);

    /** The users holding the role directly, sorted by username (not those getting it through a team). */
    List<RoleHolder> holders(String realm, String catalog, String role);
}
