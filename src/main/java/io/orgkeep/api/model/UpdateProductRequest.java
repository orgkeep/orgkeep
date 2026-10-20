package io.orgkeep.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Replaces what can change of a product. Its name cannot change, its roles are managed one by one.
 *
 * @param description optional, absent = no description
 * @param params      the whole parameter schema, an empty list removes every parameter. Checked against the values
 *                    of the orgs the product is assigned to: a new required parameter needs a default
 */
public record UpdateProductRequest(
    @NotBlank
    @Size(max = 255)
    String displayName,
    @Size(max = 255)
    String description,
    @NotNull
    List<@Valid ParamDefinition> params){ }
