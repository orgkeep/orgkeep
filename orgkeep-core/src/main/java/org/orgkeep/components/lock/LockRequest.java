package org.orgkeep.components.lock;

import java.util.Comparator;
import java.util.Locale;

/**
 * One lock a call needs: a resource (type and key) and a mode. Keys start with the realm, for the SaaS, and are built
 * by the factories below: a realm, organisation or team name is a slug and never holds a "/", and the key of a user
 * is the lower-cased email (for an add, the user may not exist yet) or the Keycloak id (for an existing user).
 * <p>
 * {@code byId} is only meaningful for a USER: it ranks the id after the email. It is set by the factories and never
 * read back from the key, which holds an email that may contain anything, "/id:" included.
 */
public record LockRequest(ResourceType type, String key, LockMode mode, boolean byId) {
    public LockRequest {
        if (byId && type != ResourceType.USER) {
            throw new IllegalArgumentException("Only a USER lock can be by id, not " + type);
        }
    }

    /** Acquisition order: the type, then for a user the email before the id, then the key. */
    static final Comparator<LockRequest> RANK = Comparator
            .comparing(LockRequest::type)
            .thenComparingInt(LockRequest::rankInType)
            .thenComparing(LockRequest::key);

    public static LockRequest org(String realm, String org, LockMode mode) {
        return new LockRequest(ResourceType.ORG, realm + "/" + org, mode, false);
    }

    public static LockRequest team(String realm, String org, String team, LockMode mode) {
        return new LockRequest(ResourceType.TEAM, realm + "/" + org + "/" + team, mode, false);
    }

    public static LockRequest userByEmail(String realm, String email, LockMode mode) {
        return new LockRequest(ResourceType.USER, realm + "/email:" + email.toLowerCase(Locale.ROOT), mode, false);
    }

    public static LockRequest userById(String realm, String userId, LockMode mode) {
        return new LockRequest(ResourceType.USER, realm + "/id:" + userId, mode, true);
    }

    /** The registry key: the type is part of it, an org and a team can never share an entry. */
    String registryKey() {
        return type + ":" + key;
    }

    private int rankInType() {
        return byId ? 1 : 0;
    }
}
