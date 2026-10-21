package io.orgkeep.components.organisation.usergroup.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON values written on the groups of the teams, that the native mappers of a product put in the tokens as they
 * are: one value per quota pool ({@code <catalog>.<product>.quotas}), one object for the parameters
 * ({@code <catalog>.<product>.params}).
 */
public final class TeamClaims {
    /** Keycloak stores a group attribute value in 255 characters. */
    public static final int MAX_VALUE_LENGTH = 255;

    private static final ObjectMapper JSON = new ObjectMapper();

    private TeamClaims() {
    }

    /**
     * One pool of a product, e.g. {"pool":"acme","limit":1000,"cycle":"monthly"}. Always built the same way, fields in
     * the same order: Keycloak only merges identical values, and the pool of the org is carried by every team giving
     * the product. Pool keys are made of slugs, nothing to escape.
     */
    public static String pool(String pool, long limit, QuotaCycle cycle) {
        return "{\"pool\":\"%s\",\"limit\":%d,\"cycle\":\"%s\"}".formatted(pool, limit, cycle.getValue());
    }

    /**
     * One pool of a metered feature, e.g. {"feature":"scan","pool":"acme","limit":200,"cycle":"daily"}: the API keys
     * its counter by (catalog, product, feature, pool). Same rule as {@link #pool(String, long, QuotaCycle)}: always
     * built the same way, feature first, so that Keycloak merges the pool of the org carried by several teams.
     */
    public static String pool(String feature, String pool, long limit, QuotaCycle cycle) {
        return "{\"feature\":\"%s\",\"pool\":\"%s\",\"limit\":%d,\"cycle\":\"%s\"}"
                .formatted(feature, pool, limit, cycle.getValue());
    }

    /**
     * The parameter values of an org as a JSON object, typed after the schema ({"region":"eu","seats":10}), keys sorted
     * so that the value is stable. Null without any value: the token then has no "params".
     */
    public static String params(List<ParamDefinition> schema, Map<String, String> values) {
        if (values.isEmpty()) {
            return null;
        }
        Map<String, ParamType> types = new HashMap<>();
        schema.forEach(param -> types.put(param.name(), param.type()));

        ObjectNode node = JSON.createObjectNode();
        new TreeMap<>(values).forEach((name, value) -> {
            switch (types.getOrDefault(name, ParamType.STRING)) {
                case NUMBER -> putNumber(node, name, value);
                case BOOLEAN -> node.put(name, Boolean.parseBoolean(value));
                default -> node.put(name, value);
            }
        });
        return node.toString();
    }

    /**
     * @throws InvalidRequestException if the parameter values of an org would not fit in a group attribute
     */
    public static void checkParamsFit(List<ParamDefinition> schema, Map<String, String> values) {
        String params = params(schema, values);
        if (params != null && params.length() > MAX_VALUE_LENGTH) {
            throw new InvalidRequestException("The parameter values take %d characters in the token, %d at most: %s"
                    .formatted(params.length(), MAX_VALUE_LENGTH, params));
        }
    }

    private static void putNumber(ObjectNode node, String name, String value) {
        try {
            node.put(name, new BigDecimal(value));
        } catch (NumberFormatException e) {
            // checked by ParamRules when assigned: only a value changed outside of Orgkeep gets here, kept as text
            node.put(name, value);
        }
    }
}
