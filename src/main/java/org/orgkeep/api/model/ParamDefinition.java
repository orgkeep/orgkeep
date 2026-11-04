package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A parameter of a product, whose value each org negotiates (e.g. "region"). The rules between the fields
 * (values of an enum, default of the right type) are checked by the service.
 *
 * @param name         lowercase slug, neither "quota" nor "quota-cycle" (standard parameters of every product)
 * @param required     an org must have a value: given at assignment, or the default
 * @param defaultValue optional, applied when an org gives no value
 * @param values       the allowed values of an enum, empty for the other types
 */
public record ParamDefinition(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String name,
    @NotNull
    ParamType type,
    boolean required,
    @Size(max = 100)
    String defaultValue,
    List<@NotBlank @Size(max = 100) String> values){

    public ParamDefinition {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
