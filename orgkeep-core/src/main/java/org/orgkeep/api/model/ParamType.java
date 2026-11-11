package org.orgkeep.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/** Type of a parameter of a product: checks the values an org negotiates, and their JSON type in the token. */
@AllArgsConstructor
public enum ParamType {
    NUMBER("number"),
    STRING("string"),
    ENUM("enum"),
    BOOLEAN("boolean");

    /** Value stored in the Keycloak attribute. */
    @Getter
    private final String value;

    public static Optional<ParamType> fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
