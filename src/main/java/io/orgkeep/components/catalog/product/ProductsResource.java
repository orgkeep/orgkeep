package io.orgkeep.components.catalog.product;

import io.orgkeep.api.ProductService;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.UpdateFeatureRequest;
import io.orgkeep.api.model.UpdateProductRequest;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Products of a catalog, managed by the realm owner.
 * <p>
 * Sub path of /admin/catalogs declared in its own class: JAX-RS picks the class with the most specific path.
 */
@Path("/admin/catalogs/{catalog}/products")
@RolesAllowed("realm-owner")
@Tag(name = "Products", description = "Products of a catalog, with their features and parameters, managed by the realm owner")
@RequiredArgsConstructor
public class ProductsResource {
    private final ProductService productService;
    private final CurrentRealm currentRealm;

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create a product",
            description = "Adds the product and its features to the catalog client, and the claims of its quotas and parameters to the scope of the catalog.")
    @APIResponseSchema(value = Product.class, responseCode = "201", responseDescription = "Product created")
    @APIResponse(responseCode = "400", description = "Invalid request or inconsistent parameter schema")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    @APIResponse(responseCode = "409", description = "A product or a catalog role already has this name")
    public Product create(@PathParam("catalog") String catalog, @Valid @NotNull CreateProductRequest request){
        return productService.create(currentRealm.name(), catalog, request);
    }

    @GET
    @Operation(summary = "List the products of the catalog", description = "Sorted by name, with the number of organisations each is assigned to.")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    public List<Product> list(@PathParam("catalog") String catalog){
        return productService.list(currentRealm.name(), catalog);
    }

    @GET
    @Path("/{product}")
    @Operation(summary = "Get a product")
    @APIResponseSchema(value = Product.class, responseCode = "200", responseDescription = "The product")
    @APIResponse(responseCode = "404", description = "Unknown catalog or product")
    public Product get(@PathParam("catalog") String catalog, @PathParam("product") String product){
        return productService.get(currentRealm.name(), catalog, product);
    }

    @PUT
    @Path("/{product}")
    @Operation(summary = "Update a product",
            description = "Replaces its display name, description and parameter schema. The values of the organisations follow: "
                    + "a removed parameter is removed, a new one gets its default.")
    @APIResponseSchema(value = Product.class, responseCode = "200", responseDescription = "Product updated")
    @APIResponse(responseCode = "400", description = "Inconsistent schema, or a schema the values of an organisation do not fit")
    @APIResponse(responseCode = "404", description = "Unknown catalog or product")
    public Product update(@PathParam("catalog") String catalog, @PathParam("product") String product,
                          @Valid @NotNull UpdateProductRequest request){
        return productService.update(currentRealm.name(), catalog, product, request);
    }

    @DELETE
    @Path("/{product}")
    @Operation(summary = "Delete a product", description = "With its features. Refused while the product is assigned to an organisation.")
    @APIResponse(responseCode = "204", description = "Product deleted")
    @APIResponse(responseCode = "404", description = "Unknown catalog or product")
    @APIResponse(responseCode = "409", description = "The product is still assigned to an organisation")
    public void delete(@PathParam("catalog") String catalog, @PathParam("product") String product){
        productService.delete(currentRealm.name(), catalog, product);
    }

    // PUT: creates the feature, or updates its display name
    @PUT
    @Path("/{product}/features/{feature}")
    @Operation(summary = "Add or update a feature of the product",
            description = "In the tokens: \"<product>:<feature>\" in resource_access.<catalog>.roles, for the users of the organisations that bought it. "
                    + "\"metered\" (the org buys it with a quota) is only read when the feature is created.")
    @APIResponseSchema(value = Product.class, responseCode = "200", responseDescription = "The product, with its features")
    @APIResponse(responseCode = "400", description = "Invalid feature name")
    @APIResponse(responseCode = "404", description = "Unknown catalog or product")
    @APIResponse(responseCode = "409", description = "Another \"metered\" for an existing feature, or a first metered feature on a product assigned to organisations")
    public Product putFeature(@PathParam("catalog") String catalog, @PathParam("product") String product,
                              @PathParam("feature") String feature, @Valid UpdateFeatureRequest request){
        return productService.putFeature(currentRealm.name(), catalog, product, feature, request);
    }

    @DELETE
    @Path("/{product}/features/{feature}")
    @Operation(summary = "Remove a feature of the product", description = "The organisations that bought it lose it.")
    @APIResponseSchema(value = Product.class, responseCode = "200", responseDescription = "The product, with its features")
    @APIResponse(responseCode = "404", description = "Unknown catalog, product or feature")
    @APIResponse(responseCode = "409", description = "A metered feature bought by an organisation, or the last metered feature of an assigned product")
    public Product deleteFeature(@PathParam("catalog") String catalog, @PathParam("product") String product,
                                 @PathParam("feature") String feature){
        return productService.deleteFeature(currentRealm.name(), catalog, product, feature);
    }
}
