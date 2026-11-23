package org.orgkeep.components.catalog;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.exceptions.CatalogNotFoundException;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.web.CurrentRealm;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Caching of {@link CatalogSetup#get} (realm, name -> ids, #56): without it, a catalog is resolved by reading every
 * client scope of the realm with its mappers, up to 3 times in one {@code GET /organisations/{org}/products}. Each
 * test uses its own catalog so the tests do not interfere with each other through the shared cache.
 * <p>
 * {@code deletingTheCatalogEvictsTheCachedIds} checks the cache directly (injecting its {@code Cache} bean, read as
 * a {@link CaffeineCache} for {@code getIfPresent}): the final answer of {@code get} after a delete would self-heal
 * (verify-on-hit, never cache an absence) even if {@code delete} never evicted anything, since the catalog is really
 * gone and {@link KeycloakClients#matches} would fail the verification anyway. {@code
 * getVerifiesACacheHitInsteadOfSearchingAgain} verifies, through a spy, that a hit calls {@code matches} rather than
 * resolving the catalog again.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSetupTest {
    @Inject CatalogSetup catalogSetup;
    @Inject CatalogService catalogService;
    @InjectMock CurrentRealm currentRealm;
    @InjectSpy KeycloakClients clients;
    @CacheName(CatalogSetup.CATALOG_IDS_CACHE) Cache catalogIdsCache;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    // A cached result is only trusted after a cheap GET confirms its client still exists: a catalog removed outside
    // Orgkeep (the console, or a test's raw cleanup, e.g. a catalog with products the service refuses to delete) is
    // resolved again instead of masking its absence forever.
    @Test
    void getSelfHealsWhenTheCatalogIsRemovedOutsideOrgkeep() {
        String name = "removed-outside-orgkeep";
        catalogService.create(testRealm.name(), new CreateCatalogRequest(name, "Removed", null));
        catalogSetup.get(testRealm.name(), name); // resolved once, now cached

        // removed directly in Keycloak, bypassing CatalogSetup.evict: the cache is not evicted by that path
        removeClientAndScope(name);

        assertThrows(CatalogNotFoundException.class, () -> catalogSetup.get(testRealm.name(), name),
                "a stale cache entry would still answer with the removed catalog's ids instead of resolving again");
    }

    // CatalogServiceImpl.remove deletes the scope first, then the client: the admin API is not transactional, so a
    // failure on the client delete leaves the scope really gone but the client still there. The cache must not
    // answer with that half-deleted state: remove() now evicts first, before either delete is attempted.
    @Test
    void deletingWithAFailedClientRemovalStillEvictsTheCachedIds() {
        String name = "partial-delete-failure";
        catalogService.create(testRealm.name(), new CreateCatalogRequest(name, "Partial", null));
        catalogSetup.get(testRealm.name(), name); // resolved once, now cached

        Mockito.doThrow(new RuntimeException("simulated failure"))
                .when(clients).delete(eq(testRealm.name()), eq(name), anyString());

        assertThrows(RuntimeException.class, () -> catalogService.delete(testRealm.name(), name));

        // the scope really was deleted (the client delete was only mocked to fail): evicting after it, rather than
        // before, would leave the cache answering with the removed scope's id instead of resolving again
        assertThrows(CatalogNotFoundException.class, () -> catalogSetup.get(testRealm.name(), name),
                "a stale cache entry would still answer with the removed scope's id");
    }

    @Test
    void deletingTheCatalogEvictsTheCachedIds() {
        String name = "deleted-catalog";
        catalogService.create(testRealm.name(), new CreateCatalogRequest(name, "Deleted", null));
        CatalogSetup.Ids ids = catalogSetup.get(testRealm.name(), name); // resolved once, now cached
        assertEquals(Optional.of(ids), cachedIds(name), "get should have cached the ids");

        catalogService.delete(testRealm.name(), name);

        assertEquals(Optional.empty(), cachedIds(name),
                "delete must evict the cache entry itself: a CatalogNotFoundException on the next get would also "
                        + "clear it (the deleted client fails matches's verification), but that is not what this "
                        + "test is checking");
    }

    // The issue's point: an id already known is not resolved again. A hit calls matches (the cheap GET that
    // verifies the client), not a fresh resolve. The verify on findUuid below (resolve's own entry point) proves
    // directly that no resolve ran on the hit, on top of the verify on matches that confirms the hit was taken.
    @Test
    void getVerifiesACacheHitInsteadOfSearchingAgain() {
        String name = "verified-on-a-hit";
        catalogService.create(testRealm.name(), new CreateCatalogRequest(name, "Verified", null));
        CatalogSetup.Ids ids = catalogSetup.get(testRealm.name(), name); // miss: resolves, then caches
        Mockito.clearInvocations(clients);

        CatalogSetup.Ids second = catalogSetup.get(testRealm.name(), name); // hit: verifies instead

        assertEquals(ids, second);
        Mockito.verify(clients, Mockito.times(1)).matches(testRealm.name(), ids.clientUuid(), name);
        Mockito.verify(clients, Mockito.never()).findUuid(Mockito.anyString(), Mockito.anyString());
    }

    private Optional<CatalogSetup.Ids> cachedIds(String name) {
        CompletableFuture<CatalogSetup.Ids> cached = catalogIdsCache.as(CaffeineCache.class)
                .getIfPresent(new CompositeCacheKey(testRealm.name(), name));
        return cached == null ? Optional.empty() : Optional.of(cached.join());
    }

    private void removeClientAndScope(String name) {
        testRealm.resource().clients().findByClientId(name)
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
        testRealm.resource().clientScopes().findAll().stream()
                .filter(scope -> name.equals(scope.getName()))
                .forEach(scope -> testRealm.resource().clientScopes().get(scope.getId()).remove());
    }
}
