package org.orgkeep.components.realm;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Plain unit test, no @QuarkusTest: the generator needs neither CDI nor Keycloak, a "new" is enough and runs in ms
class PasswordGeneratorTest {

    private final PasswordGenerator generator = new PasswordGenerator();

    // random output: repeated, so that a missing character class shows up even if one draw happens to contain it
    @RepeatedTest(50)
    void containsEveryCharacterClass() {
        String password = generator.generate();

        assertEquals(PasswordGenerator.LENGTH, password.length());
        assertTrue(containsAny(password, PasswordGenerator.LOWER), password);
        assertTrue(containsAny(password, PasswordGenerator.UPPER), password);
        assertTrue(containsAny(password, PasswordGenerator.DIGITS), password);
        assertTrue(containsAny(password, PasswordGenerator.SYMBOLS), password);
    }

    @Test
    void neverReturnsTheSamePasswordTwice() {
        Set<String> passwords = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> passwords.add(generator.generate()));
        assertEquals(1000, passwords.size());
    }

    private static boolean containsAny(String password, String alphabet) {
        return password.chars().anyMatch(c -> alphabet.indexOf(c) >= 0);
    }
}
