package io.orgkeep.components.catalog.product;

import io.orgkeep.api.ProductService;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Assignment of the products of a catalog to an organisation, with what it negotiated, managed by the realm owner.
 */
@Path("/admin/organisations/{org}/catalogs/{catalog}/products/{product}")
@RolesAllowed("realm-owner")
@Tag(name = "Organisation products", description = "Products assigned to an organisation, managed by the realm owner")
@RequiredArgsConstructor
public class OrgCatalogsResource {
    private final ProductService productService;
    private final CurrentRealm currentRealm;

    // PUT rather than POST: assigning again replaces what the org negotiated
    @PUT
    @Operation(summary = "Assign a product",
            description = "Assigns the product to the organisation with the features it bought, its quotas and parameters, "
                    + "or replaces them if the product is already assigned. A simple product takes a quota and its cycle; "
                    + "a product with metered features takes no quota of product but one quota per metered feature bought "
                    + "(featureQuotas), and at least one feature bought.")
    @APIResponseSchema(value = OrgProduct.class, responseCode = "200", responseDescription = "Product assigned")
    @APIResponse(responseCode = "400", description = "Invalid request, unknown bought feature, quotas that do not fit the kind of the product, or parameters that do not fit the schema")
    @APIResponse(responseCode = "404", description = "Unknown organisation, catalog or product")
    public OrgProduct assign(@PathParam("org") String org, @PathParam("catalog") String catalog,
                             @PathParam("product") String product, @Valid @NotNull AssignProductRequest request){
        return productService.assign(currentRealm.name(), org, catalog, product, request);
    }

    @DELETE
    @Operation(summary = "Unassign a product", description = "The members of the organisation lose the product.")
    @APIResponse(responseCode = "204", description = "Product unassigned")
    @APIResponse(responseCode = "404", description = "Unknown organisation or product not assigned")
    public void unassign(@PathParam("org") String org, @PathParam("catalog") String catalog,
                         @PathParam("product") String product){
        productService.unassign(currentRealm.name(), org, catalog, product);
    }
}
