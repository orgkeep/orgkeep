package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param name        clientId of the catalog client, name of its client scope and of its claim in the access tokens,
 *                    hence a lowercase slug that is not a reserved claim (see {@link Catalogs})
 * @param displayName name shown to the realm owner and the org admins
 * @param description optional
 */
public record CreateCatalogRequest(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    @CatalogName
    String name,
    @NotBlank
    String displayName,
    String description){ }
