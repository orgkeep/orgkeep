package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A feature of a product: a permission ("read", "write", "stats") or, if metered, a capacity an org buys with its own
 * quota ("search", "dig"). In the tokens: "&lt;product&gt;:&lt;name&gt;" in resource_access.&lt;catalog&gt;.roles.
 *
 * @param name        lowercase slug, unique in its product
 * @param displayName optional
 * @param metered     true if the org buys it with a quota. Set when the feature is created, never changed afterwards
 */
public record Feature(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String name,
    @Size(max = 255)
    String displayName,
    boolean metered){

    /** A feature without quota: a permission. */
    public Feature(String name, String displayName) {
        this(name, displayName, false);
    }
}
