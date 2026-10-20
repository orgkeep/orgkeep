package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A role of a product: an option an org buys ("scan", "flow") or a permission ("read", "write").
 * In the tokens: "&lt;product&gt;:&lt;name&gt;" in resource_access.&lt;catalog&gt;.roles.
 *
 * @param name        lowercase slug, unique in its product
 * @param displayName optional
 */
public record ProductRole(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String name,
    @Size(max = 255)
    String displayName){ }
