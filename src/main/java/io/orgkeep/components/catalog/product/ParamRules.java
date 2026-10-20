package io.orgkeep.components.catalog.product;

import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.ParamDefinition;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rules of the parameters of a product: a consistent schema, and values that fit it.
 * Plain static methods, no Keycloak: they read on their own and are tested without Quarkus (ParamRulesTest).
 */
final class ParamRules {

    private ParamRules() {
    }

    /** @throws InvalidRequestException if the schema is inconsistent */
    static void checkSchema(List<ParamDefinition> schema) {
        Set<String> names = new HashSet<>();
        for (ParamDefinition param : schema) {
            if (!names.add(param.name())) {
                throw new InvalidRequestException("Parameter '%s' is defined twice".formatted(param.name()));
            }
            if (CatalogRoles.STANDARD_PARAMS.contains(param.name())) {
                throw new InvalidRequestException(
                        "'%s' is a standard parameter of every product, it cannot be defined".formatted(param.name()));
            }
            switch (param.type()) {
                case ENUM -> {
                    if (param.values().isEmpty()) {
                        throw new InvalidRequestException("Enum parameter '%s' has no values".formatted(param.name()));
                    }
                }
                default -> {
                    if (!param.values().isEmpty()) {
                        throw new InvalidRequestException(
                                "Only an enum parameter has values, not '%s'".formatted(param.name()));
                    }
                }
            }
            if (param.defaultValue() != null) {
                checkValue(param, param.defaultValue());
            }
        }
    }

    /**
     * Checks the values an org negotiated against the schema, and applies the defaults.
     *
     * @return the values to store, sorted by name: every parameter with a value, given or default
     * @throws InvalidRequestException if a value is unknown, of the wrong type, or a required parameter has none
     */
    static Map<String, String> resolveValues(List<ParamDefinition> schema, Map<String, String> values) {
        Set<String> known = new HashSet<>();
        schema.forEach(param -> known.add(param.name()));
        List<String> unknown = values.keySet().stream().filter(name -> !known.contains(name)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Unknown parameters: %s".formatted(unknown));
        }

        Map<String, String> resolved = new TreeMap<>();
        for (ParamDefinition param : schema) {
            String value = values.containsKey(param.name()) ? values.get(param.name()) : param.defaultValue();
            if (value == null) {
                if (param.required()) {
                    throw new InvalidRequestException("Parameter '%s' is required".formatted(param.name()));
                }
                continue;
            }
            checkValue(param, value);
            resolved.put(param.name(), value);
        }
        return resolved;
    }

    private static void checkValue(ParamDefinition param, String value) {
        boolean valid = switch (param.type()) {
            case NUMBER -> isNumber(value);
            case BOOLEAN -> "true".equals(value) || "false".equals(value);
            case ENUM -> param.values().contains(value);
            case STRING -> true;
        };
        if (!valid) {
            throw new InvalidRequestException("Value '%s' does not fit the %s parameter '%s'%s".formatted(
                    value, param.type().getValue(), param.name(),
                    param.values().isEmpty() ? "" : " " + param.values()));
        }
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
