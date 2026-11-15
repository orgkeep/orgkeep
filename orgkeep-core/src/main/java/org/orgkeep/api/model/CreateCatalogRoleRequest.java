package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param name         role name in the catalog client, shared with the products: a lowercase slug
 * @param displayName  optional
 * @param description  optional
 * @param orgGrantable see {@link CatalogRole}
 */
public record CreateCatalogRoleRequest(
    @NotBlank
    @Size(max = 50)
    @Slug
    String name,
    @Size(max = 255)
    String displayName,
    @Size(max = 255)
    String description,
    boolean orgGrantable){ }
