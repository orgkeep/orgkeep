package org.orgkeep.repository.keycloak;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The admin client is one bean for the whole application (KeycloakAdminClientProducer), not one rebuilt (and
 * granted a fresh token) per HTTP request as the default bean of quarkus-keycloak-admin-rest-client would be (a
 * @RequestScoped synthetic bean).
 */
@QuarkusTest
class KeycloakAdminClientProducerTest {
    @Inject
    KeycloakClients clients;

    @Test
    void theAdminClientIsASingletonBean() {
        InjectableBean<Keycloak> bean = Arc.container().instance(Keycloak.class).getBean();
        assertEquals(Singleton.class, bean.getScope());
    }

    /**
     * The OIDC blocking executor that reads the dashboard client (DashboardClientImpl) has no request context at
     * all, unlike this test method: this proves a repository behind the admin client no longer needs one (it used
     * to, back when the admin client itself was @RequestScoped).
     */
    @Test
    void aRepositoryWorksOnAThreadWithoutARequestContext() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> result = executor.submit(() -> clients.findUuid("master", "no-such-client"));
            assertEquals(Optional.empty(), result.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
