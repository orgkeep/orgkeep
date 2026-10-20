package io.orgkeep.api.model;

import jakarta.validation.constraints.Size;

/** Creates or updates a role of a product, named by the path. @param displayName optional */
public record UpdateProductRoleRequest(
    @Size(max = 255)
    String displayName){ }
