package org.orgkeep.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * What an organisation negotiated for a product. The quotas are always required, checked by the service as they
 * depend on the kind of the product: an org never gets unlimited access by mistake.
 * <ul>
 *   <li>simple product (no metered feature): {@code quota} and {@code cycle}, no {@code featureQuotas};</li>
 *   <li>metered product: no {@code quota} nor {@code cycle}, at least one feature bought, and one entry of
 *       {@code featureQuotas} per metered feature bought, no more, no less.</li>
 * </ul>
 *
 * @param quota          quota of the org for a simple product
 * @param cycle          its cycle
 * @param boughtFeatures optional, features of the product the org bought (by name, without the product prefix)
 * @param featureQuotas  metered product: the quota of the org for each metered feature it bought, by feature name
 * @param params         optional, values of the parameters of the product, checked against its schema
 */
public record AssignProductRequest(
    @Positive
    Long quota,
    QuotaCycle cycle,
    List<@NotBlank String> boughtFeatures,
    Map<@NotBlank String, @NotNull @Valid FeatureQuota> featureQuotas,
    Map<@NotBlank String, @NotNull @Size(max = 100) String> params){

    public AssignProductRequest {
        boughtFeatures = boughtFeatures == null ? List.of() : List.copyOf(boughtFeatures);
        featureQuotas = featureQuotas == null ? Map.of() : Map.copyOf(featureQuotas);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A simple product, with the features the org bought and the values of its parameters. */
    public AssignProductRequest(Long quota, QuotaCycle cycle, List<String> boughtFeatures, Map<String, String> params) {
        this(quota, cycle, boughtFeatures, Map.of(), params);
    }

    /** A simple product without features nor parameters. */
    public AssignProductRequest(Long quota, QuotaCycle cycle) {
        this(quota, cycle, List.of(), Map.of());
    }

    /** A metered product without parameters: the features bought, and a quota per metered one. */
    public static AssignProductRequest metered(List<String> boughtFeatures, Map<String, FeatureQuota> featureQuotas) {
        return new AssignProductRequest(null, null, boughtFeatures, featureQuotas, Map.of());
    }
}
