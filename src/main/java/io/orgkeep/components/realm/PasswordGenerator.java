package io.orgkeep.components.realm;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Random passwords for the accounts created without one. Nobody knows them: they are never logged nor returned,
 * the user sets their own password from the invitation email.
 */
@ApplicationScoped
public class PasswordGenerator {
    static final int LENGTH = 24;

    // look-alike characters (l, I, O, 0, 1) left out, in case a password is ever read by a human
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String DIGITS = "23456789";
    static final String SYMBOLS = "!@#$%^&*-_=+?";
    private static final String ALL = LOWER + UPPER + DIGITS + SYMBOLS;

    // thread-safe, a single instance is enough for the application
    private final SecureRandom random = new SecureRandom();

    /** At least one character of each class, so that a usual Keycloak password policy accepts it. */
    public String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        chars.add(pick(LOWER));
        chars.add(pick(UPPER));
        chars.add(pick(DIGITS));
        chars.add(pick(SYMBOLS));
        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }
        // otherwise the first four characters would always be lower, upper, digit, symbol
        Collections.shuffle(chars, random);

        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
