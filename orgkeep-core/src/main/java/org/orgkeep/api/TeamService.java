package org.orgkeep.api;

import org.orgkeep.api.model.AvailableProduct;
import org.orgkeep.api.model.CatalogRole;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.TeamProduct;

import java.util.List;

/**
 * What the teams of an organisation (its user groups) give, managed by its org admins: products of the org, with their
 * features and a quota of team, and catalog roles. Written ahead on the group of the team (role mappings and attributes),
 * read by the native mappers of Keycloak when a token is issued: nothing is computed per user.
 */
public interface TeamService {

    /**
     * The products assigned to the org, with the features it bought: what its teams can give.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     */
    List<AvailableProduct> availableProducts(String realm, String orgName);

    /**
     * The catalogs where the org has at least one product, sorted by name.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     */
    List<String> catalogs(String realm, String orgName);

    /**
     * The catalog roles its org admins can give through their teams.
     *
     * @throws org.orgkeep.api.exceptions.CatalogNotAssignedException if the org has no product in this catalog
     */
    List<CatalogRole> grantableRoles(String realm, String orgName, String catalog);

    /**
     * Gives the product to the team, or replaces what the team gives of it.
     *
     * @throws org.orgkeep.api.exceptions.ProductNotAssignedException if the product is not assigned to the org
     * @throws org.orgkeep.api.exceptions.InvalidRequestException for a feature the org did not buy, a cycle without quota,
     *                                                           or, for a metered product, a quota of team or no feature
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the team or the org
     */
    TeamProduct grantProduct(String realm, String orgName, String team, String catalog, String product,
                             GrantProductRequest request);

    /**
     * The team no longer gives the product. Idempotent.
     *
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the team or the org
     */
    void revokeProduct(String realm, String orgName, String team, String catalog, String product);

    /**
     * Gives an org grantable catalog role to the team. Idempotent.
     *
     * @throws org.orgkeep.api.exceptions.CatalogRoleNotFoundException if the catalog has no such catalog role
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException for a role reserved to the realm owner, or a catalog
     *                                                               where the org has no product
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the team or the org
     */
    void grantCatalogRole(String realm, String orgName, String team, String catalog, String role);

    /**
     * The team no longer gives the catalog role. Idempotent.
     *
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the team or the org
     */
    void revokeCatalogRole(String realm, String orgName, String team, String catalog, String role);
}
