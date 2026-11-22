package org.orgkeep.fixtures;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

/**
 * The Keycloak admin client (and anything that calls it, like the services of {@code api}) is a @RequestScoped
 * bean: Quarkus keeps a request context active around a test method (and its @BeforeEach/@AfterEach), but not
 * around @BeforeAll/@AfterAll, which run once for the whole class with @TestInstance(PER_CLASS). A call to such a
 * service from there needs a request context of its own.
 */
public final class RequestScope {
    private RequestScope() {
    }

    public static void run(Runnable action) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activated = !requestContext.isActive();
        if (activated) {
            requestContext.activate();
        }
        try {
            action.run();
        } finally {
            if (activated) {
                requestContext.terminate();
            }
        }
    }
}
