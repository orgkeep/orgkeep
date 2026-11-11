package org.orgkeep.components.catalog.product;

import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.model.ParamDefinition;
import org.orgkeep.api.model.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test: ParamRules has no dependency, no Quarkus nor Keycloak is started (no @QuarkusTest), it runs in
 * milliseconds. The rules are tested here one by one; the Keycloak side is covered by ProductServiceTest.
 */
class ParamRulesTest {
    private static final ParamDefinition REGION = new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us"));
    private static final ParamDefinition SEATS = new ParamDefinition("seats", ParamType.NUMBER, false, null, List.of());
    private static final ParamDefinition SSO = new ParamDefinition("sso", ParamType.BOOLEAN, false, "false", List.of());

    @Test
    void consistentSchema() {
        assertDoesNotThrow(() -> ParamRules.checkSchema(List.of(REGION, SEATS, SSO)));
    }

    @Test
    void schemaErrors() {
        // each line: one rule broken
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(SEATS, SEATS)));
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(
                new ParamDefinition("quota", ParamType.NUMBER, false, null, List.of()))));
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(
                new ParamDefinition("region", ParamType.ENUM, false, null, List.of()))));
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(
                new ParamDefinition("seats", ParamType.NUMBER, false, null, List.of("1", "2")))));
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(
                new ParamDefinition("region", ParamType.ENUM, false, "asia", List.of("eu", "us")))));
        assertThrows(InvalidRequestException.class, () -> ParamRules.checkSchema(List.of(
                new ParamDefinition("seats", ParamType.NUMBER, false, "many", List.of()))));
    }

    // the defaults are applied, a parameter without value nor default is simply absent
    @Test
    void resolveAppliesDefaults() {
        assertEquals(Map.of("region", "eu", "sso", "false"),
                ParamRules.resolveValues(List.of(REGION, SEATS, SSO), Map.of()));
        assertEquals(Map.of("region", "us", "seats", "12.5", "sso", "true"),
                ParamRules.resolveValues(List.of(REGION, SEATS, SSO), Map.of("region", "us", "seats", "12.5", "sso", "true")));
    }

    @Test
    void resolveErrors() {
        ParamDefinition requiredWithoutDefault = new ParamDefinition("tenant", ParamType.STRING, true, null, List.of());

        assertThrows(InvalidRequestException.class, () -> ParamRules.resolveValues(List.of(requiredWithoutDefault), Map.of()));
        assertThrows(InvalidRequestException.class, () -> ParamRules.resolveValues(List.of(REGION), Map.of("unknown", "x")));
        assertThrows(InvalidRequestException.class, () -> ParamRules.resolveValues(List.of(REGION), Map.of("region", "asia")));
        assertThrows(InvalidRequestException.class, () -> ParamRules.resolveValues(List.of(SEATS), Map.of("seats", "ten")));
        assertThrows(InvalidRequestException.class, () -> ParamRules.resolveValues(List.of(SSO), Map.of("sso", "yes")));
    }
}
