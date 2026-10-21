package io.orgkeep.api.model;

import jakarta.validation.constraints.Size;

/**
 * Creates or updates a feature of a product, named by the path.
 *
 * @param displayName optional
 * @param metered     only read when the feature is created (null: false). Never changes afterwards
 */
public record UpdateFeatureRequest(
    @Size(max = 255)
    String displayName,
    Boolean metered){

    /** A feature without quota, or the new display name of an existing feature. */
    public UpdateFeatureRequest(String displayName) {
        this(displayName, null);
    }
}
