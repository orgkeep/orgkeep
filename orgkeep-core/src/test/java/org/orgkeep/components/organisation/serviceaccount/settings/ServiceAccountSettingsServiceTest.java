package org.orgkeep.components.organisation.serviceaccount.settings;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.ServiceAccountSettingsService;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.exceptions.ServiceAccountNotFoundException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * What the settings write in Keycloak (a real one, Dev Services), in a throwaway realm per test: the realm attribute,
 * the client attribute and the marker scope of a service account, the order of the writes, the locks.
 */
@QuarkusTest
class ServiceAccountSettingsServiceTest {
    private static final String ORG = "acme";
    private static final Set<String> BASE_SCOPES = Set.of("basic", "roles", "service_account", "organization", "prod");

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject ServiceAccountSettingsService settingsService;
    @Inject ResourceLocks locks;
    // a spy on the real repository: it calls Keycloak as usual, a test can check the order of the calls or make one fail
    @InjectSpy KeycloakClients clients;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        catalogService.create(realm(), new CreateCatalogRequest("prod", "Production", null));
        productService.create(realm(), "prod", new CreateProductRequest("billing", "Billing", null));
        productService.assign(realm(), ORG, "prod", "billing", new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void realmDefaultIsFalseThenFollowsThePut() {
        assertFalse(settingsService.realmDefaults(realm()).lightweightTokens());

        assertTrue(settingsService.updateRealmDefaults(realm(), request(true)).lightweightTokens());
        assertTrue(settingsService.realmDefaults(realm()).lightweightTokens());
        assertEquals("true", realmAttributes().get(LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE));

        assertFalse(settingsService.updateRealmDefaults(realm(), request(false)).lightweightTokens());
        assertEquals("false", realmAttributes().get(LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE));
    }

    @Test
    void realmPutKeepsTheOtherRealmAttributes() {
        RealmRepresentation realm = testRealm.resource().toRepresentation();
        Map<String, String> attributes = new HashMap<>(realm.getAttributes());
        attributes.put("something.else", "kept");
        realm.setAttributes(attributes);
        testRealm.resource().update(realm);

        settingsService.updateRealmDefaults(realm(), request(true));

        assertEquals("kept", realmAttributes().get("something.else"));
        assertEquals("true", realmAttributes().get(LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE));
        // what Keycloak put there itself is still there (the realm representation is not replaced)
        assertTrue(realmAttributes().size() >= 2);
    }

    // the default is for the service accounts created afterwards only
    @Test
    void realmPutDoesNotChangeAnExistingServiceAccount() {
        create("before");

        settingsService.updateRealmDefaults(realm(), request(true));

        assertNull(client("before").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertFalse(scopes("before").contains(LightweightTokens.MARKER_CLAIM));
        assertFalse(settingsService.get(realm(), ORG, "before").lightweightTokens());
    }

    @Test
    void creationWithTheDefaultOnIsLightweightAndKeepsItsOtherScopes() {
        settingsService.updateRealmDefaults(realm(), request(true));

        ServiceAccountCreated created = serviceAccountService.create(realm(), ORG,
                new CreateServiceAccountRequest("batch", null, List.of("prod")));

        assertEquals("true", client("batch").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        Set<String> expected = new java.util.HashSet<>(BASE_SCOPES);
        expected.add(LightweightTokens.MARKER_CLAIM);
        assertEquals(expected, scopes("batch"));
        assertTrue(settingsService.get(realm(), ORG, "batch").lightweightTokens());
        // the representation the org admins read does not carry the mode
        assertTrue(created.serviceAccount().toString().indexOf("ightweight") < 0, created.serviceAccount().toString());
    }

    @Test
    void creationWithTheDefaultOffHasNeitherAttributeNorMarker() {
        create("batch");

        assertNull(client("batch").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertFalse(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));
        assertFalse(settingsService.get(realm(), ORG, "batch").lightweightTokens());
    }

    @Test
    void putOnThenOffFollowsTheAttributeAndTheMarker() {
        create("batch");

        assertTrue(settingsService.update(realm(), ORG, "batch", request(true)).lightweightTokens());
        assertEquals("true", client("batch").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertTrue(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));

        // repeated: nothing changes
        assertTrue(settingsService.update(realm(), ORG, "batch", request(true)).lightweightTokens());
        assertEquals("true", client("batch").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertEquals(1L, scopeCount("batch", LightweightTokens.MARKER_CLAIM));

        assertFalse(settingsService.update(realm(), ORG, "batch", request(false)).lightweightTokens());
        assertEquals("false", client("batch").getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertFalse(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));
        // the scope stays in the realm
        assertTrue(testRealm.resource().clientScopes().findAll().stream()
                .anyMatch(scope -> scope.getName().equals(LightweightTokens.MARKER_CLAIM)));
        assertEquals(BASE_SCOPES.stream().filter(name -> !name.equals("prod")).collect(Collectors.toSet()), scopes("batch"));
    }

    // a client edited in the console (marker removed by hand) is repaired by the same call again
    @Test
    void markerRemovedByHandIsPutBackByARepeatedOn() {
        create("batch");
        settingsService.update(realm(), ORG, "batch", request(true));
        String clientUuid = client("batch").getId();
        String markerId = markerScope(clientUuid).getId();
        testRealm.resource().clients().get(clientUuid).removeDefaultClientScope(markerId);
        assertFalse(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));

        settingsService.update(realm(), ORG, "batch", request(true));

        assertTrue(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));
    }

    // the update of the description rewrites the whole client: the mode stays
    @Test
    void anUpdateOfTheServiceAccountKeepsTheMode() {
        create("batch");
        settingsService.update(realm(), ORG, "batch", request(true));

        serviceAccountService.update(realm(), ORG, "batch",
                new org.orgkeep.api.model.UpdateServiceAccountRequest("Nightly", List.of()));

        assertTrue(settingsService.get(realm(), ORG, "batch").lightweightTokens());
        assertTrue(scopes("batch").contains(LightweightTokens.MARKER_CLAIM));
    }

    @Test
    void putWorksWhileTheServiceAccountAndTheOrgAreDisabled() {
        create("batch");
        serviceAccountService.setEnabled(realm(), ORG, "batch", false);
        assertTrue(settingsService.update(realm(), ORG, "batch", request(true)).lightweightTokens());

        orgService.setEnabled(realm(), ORG, false);
        assertFalse(settingsService.update(realm(), ORG, "batch", request(false)).lightweightTokens());
        assertTrue(settingsService.update(realm(), ORG, "batch", request(true)).lightweightTokens());
    }

    // turning on: the marker scope first, the client (attribute) after; turning off: the opposite
    @Test
    void writeOrder() {
        create("batch");
        String clientUuid = client("batch").getId();
        Mockito.clearInvocations(clients);

        settingsService.update(realm(), ORG, "batch", request(true));
        InOrder on = Mockito.inOrder(clients);
        on.verify(clients).makeDefaultScope(eq(realm()), eq(clientUuid), anyString());
        on.verify(clients).update(eq(realm()), eq(clientUuid), any(ClientRepresentation.class));

        Mockito.clearInvocations(clients);
        settingsService.update(realm(), ORG, "batch", request(false));
        InOrder off = Mockito.inOrder(clients);
        off.verify(clients).update(eq(realm()), eq(clientUuid), any(ClientRepresentation.class));
        off.verify(clients).removeScope(eq(realm()), eq(clientUuid), anyString());
    }

    // a failure after the mode was applied deletes the client, as for every other step of the creation
    @Test
    void failedCreationAfterTheModeIsRolledBack() {
        settingsService.updateRealmDefaults(realm(), request(true));
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(clients).regenerateSecret(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> create("batch"));

        assertTrue(testRealm.resource().clients().findByClientId("org-acme.batch").isEmpty());
        // the retry works and is lightweight again
        create("batch");
        assertTrue(settingsService.get(realm(), ORG, "batch").lightweightTokens());
    }

    @Test
    void unknownServiceAccountIsNotFound() {
        assertThrowsExactly(ServiceAccountNotFoundException.class, () -> settingsService.get(realm(), ORG, "nope"));
        assertThrowsExactly(ServiceAccountNotFoundException.class,
                () -> settingsService.update(realm(), ORG, "nope", request(true)));
    }

    // --- locks

    // a disable or a deletion of the org holds it exclusively: the change is refused, nothing is written
    @Test
    void putIsRefusedWhileTheOrgIsHeldExclusively() throws Exception {
        create("batch");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), ORG, LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class,
                    () -> settingsService.update(realm(), ORG, "batch", request(true)));
        } finally {
            executor.shutdownNow();
        }
        assertFalse(settingsService.get(realm(), ORG, "batch").lightweightTokens());
    }

    // the same lock as the update of the service account: a change in progress on it, 409 after the short wait
    @Test
    void putIsRefusedWhileTheServiceAccountIsHeld() throws Exception {
        ServiceAccountCreated created = create("batch");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor,
                LockRequest.userById(realm(), created.serviceAccount().userId(), LockMode.EXCLUSIVE))) {
            assertThrowsExactly(ResourceBusyException.class,
                    () -> settingsService.update(realm(), ORG, "batch", request(true)));
            // a read takes no lock
            assertFalse(settingsService.get(realm(), ORG, "batch").lightweightTokens());
        } finally {
            executor.shutdownNow();
        }
        assertTrue(settingsService.update(realm(), ORG, "batch", request(true)).lightweightTokens());
    }

    // --- helpers

    private ServiceAccountCreated create(String name) {
        return serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest(name, null, List.of()));
    }

    private static UpdateServiceAccountSettingsRequest request(boolean lightweight) {
        return new UpdateServiceAccountSettingsRequest(lightweight);
    }

    private Map<String, String> realmAttributes() {
        return testRealm.resource().toRepresentation().getAttributes();
    }

    private ClientRepresentation client(String name) {
        ClientRepresentation client = testRealm.resource().clients().findByClientId("org-acme." + name).getFirst();
        return testRealm.resource().clients().get(client.getId()).toRepresentation();
    }

    private ClientScopeRepresentation markerScope(String clientUuid) {
        return testRealm.resource().clients().get(clientUuid).getDefaultClientScopes().stream()
                .filter(scope -> scope.getName().equals(LightweightTokens.MARKER_CLAIM))
                .findFirst().orElseThrow();
    }

    /** The default client scopes of the service account. */
    private Set<String> scopes(String name) {
        return testRealm.resource().clients().get(client(name).getId()).getDefaultClientScopes().stream()
                .map(ClientScopeRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private long scopeCount(String name, String scope) {
        return testRealm.resource().clients().get(client(name).getId()).getDefaultClientScopes().stream()
                .filter(s -> s.getName().equals(scope))
                .count();
    }

    private String realm() {
        return testRealm.name();
    }

    /** Takes locks on its own thread and keeps them until closed: a lock belongs to its thread (see UserLocksTest). */
    private final class LockHolder implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> held;

        LockHolder(ExecutorService executor, LockRequest request) throws Exception {
            CountDownLatch taken = new CountDownLatch(1);
            held = executor.submit(() -> {
                try (HeldLocks ignored = locks.acquire(request)) {
                    taken.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(taken.await(30, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
        }
    }
}
