package io.orgkeep.dashboard.organisation;

import java.util.List;
import java.util.Map;

/**
 * Copy of AssignProductRequest of orgkeep-core. No Bean Validation and a String cycle: the core checks the values
 * (roles of the product, parameter schema) and its 400 is relayed to the front.
 *
 * @param cycle       DAILY, MONTHLY or YEARLY
 * @param boughtRoles roles of the product, by name without the product prefix
 * @param params      values of the parameters: a parameter left out gets its default in the core
 */
public record AssignProductRequest(
        Long quota,
        String cycle,
        List<String> boughtRoles,
        Map<String, String> params) { }
