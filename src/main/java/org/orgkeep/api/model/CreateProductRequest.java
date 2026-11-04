package org.orgkeep.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param name        role name in the catalog, group name under /{org}/catalogs/{catalog} and prefix of the product
 *                    features, hence restricted to a lowercase slug
 * @param displayName name shown to the realm owner and the org admins
 * @param description optional
 * @param features    optional, the features of the product
 * @param params      optional, the parameter schema besides the standard quota and cycle
 */
public record CreateProductRequest(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String name,
    @NotBlank
    @Size(max = 255)
    String displayName,
    @Size(max = 255)
    String description,
    List<@Valid Feature> features,
    List<@Valid ParamDefinition> params){

    public CreateProductRequest {
        features = features == null ? List.of() : List.copyOf(features);
        params = params == null ? List.of() : List.copyOf(params);
    }

    /** A product without features nor parameters: the simplest case. */
    public CreateProductRequest(String name, String displayName, String description) {
        this(name, displayName, description, List.of(), List.of());
    }
}
