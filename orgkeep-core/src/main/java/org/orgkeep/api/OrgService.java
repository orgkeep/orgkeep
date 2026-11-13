package org.orgkeep.api;

import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateOrgResponse;
import org.orgkeep.api.model.OrgDetails;
import org.orgkeep.api.model.OrgSummary;

import java.util.List;

/**
 * Organisations are identified by their name: it is unique in a realm, never changes,
 * and is the key of the org client (org-{name}) and of the org groups (/{name}).
 */
public interface OrgService {
    /**
     * @throws org.orgkeep.api.exceptions.OrgAlreadyExistsException if an organisation has this name
     * @throws org.orgkeep.api.exceptions.MemberOfAnotherOrgException if the owner is a member of another organisation
     * @throws org.orgkeep.api.exceptions.PrivilegedUserException if the owner holds a realm role of Orgkeep or a role
     *         of the realm-management client
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the new organisation was disabled before its
     *         owner became a member: the creation is rolled back
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on an organisation of this
     *         name or on the owner, after a short wait
     */
    CreateOrgResponse create(String realm,CreateOrgRequest request);

    /**
     * @param search  optional, matches organisation names (not exact)
     * @param catalog optional, keeps the organisations with a product of this catalog
     * @param product optional, keeps the organisations this product is assigned to (in any catalog, or in the one given).
     *                An unknown catalog or product gives an empty list, like any filter that matches nothing
     */
    List<OrgSummary> list(String realm, String search, String catalog, String product, int first, int max);

    /** @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name */
    OrgDetails get(String realm, String orgName);

    /**
     * Keycloak lets the members of a disabled organisation log in: disabling it also disables its enabled members
     * (and ends their sessions), except the privileged ones (a realm role of Orgkeep or a role of the realm-management
     * client); enabling it enables again only the members
     * it disabled. Idempotent: after a failure half way, the same call again finishes the job.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation
     *         (single try, no wait)
     */
    OrgDetails setEnabled(String realm, String orgName, boolean enabled);

    /**
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation
     *         (single try, no wait)
     */
    void delete(String realm, String orgName);
}
