package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * What an organisation negotiated for a product. Quota and cycle are required: an org never gets unlimited access
 * by mistake.
 *
 * @param boughtRoles optional, roles of the product the org bought (by name, without the product prefix)
 * @param params      optional, values of the parameters of the product, checked against its schema
 */
public record AssignProductRequest(
    @NotNull
    @Positive
    Long quota,
    @NotNull
    QuotaCycle cycle,
    List<@NotBlank String> boughtRoles,
    Map<@NotBlank String, @NotNull @Size(max = 100) String> params){

    public AssignProductRequest {
        boughtRoles = boughtRoles == null ? List.of() : List.copyOf(boughtRoles);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A product without roles nor parameters. */
    public AssignProductRequest(Long quota, QuotaCycle cycle) {
        this(quota, cycle, List.of(), Map.of());
    }
}
