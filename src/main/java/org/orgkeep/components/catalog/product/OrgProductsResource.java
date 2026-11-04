package org.orgkeep.components.catalog.product;

import org.orgkeep.api.ProductService;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Every product assigned to an organisation, whatever its catalog. Assigning one: see {@link OrgCatalogsResource}.
 * <p>
 * Sub path of /admin/organisations declared in its own class: JAX-RS picks the class with the most specific path.
 * A class at /admin/organisations/{org} would also catch GET /admin/organisations/{org}.
 */
@Path("/admin/organisations/{org}/products")
@RolesAllowed("realm-owner")
@Tag(name = "Organisation products", description = "Products assigned to an organisation, managed by the realm owner")
@RequiredArgsConstructor
public class OrgProductsResource {
    private final ProductService productService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List the products of the organisation", description = "Every assignment, in every catalog, sorted by catalog then product.")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<OrgProduct> list(@PathParam("org") String org){
        return productService.listAssigned(currentRealm.name(), org);
    }
}
