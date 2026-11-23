package org.orgkeep.repository.keycloak;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.ClientRepresentation;
import org.mockito.Mockito;
import org.orgkeep.fixtures.TestRealm;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caching of {@link KeycloakClients#findUuid} (realm, clientId -> internal id, #56): a client's id never changes
 * while it exists, so it is looked up once and kept, instead of searching again on every call. Against the real
 * Keycloak of Dev Services, since the cache (a constructor-injected {@code Cache} bean, see {@code lombok.config})
 * is only wired by CDI on the injected bean: a plain {@code new KeycloakClients(...)} would have no cache to use.
 * <p>
 * Most tests check the final answer of {@code findUuid}, which a correct self-heal (verify-on-hit, never cache an
 * absence) would also produce even if eviction on delete/create never ran: that alone would not catch a {@code put}
 * or {@code invalidate} silently never executing. The eviction tests below instead inspect the cache directly
 * (injecting its {@code Cache} bean, read as a {@link CaffeineCache} for {@code getIfPresent}), and one test
 * verifies, through a spy, that a hit calls {@link KeycloakClients#matches} rather than searching again.
 * <p>
 * Each test uses its own clientId so the tests do not interfere with each other through the shared cache.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakClientsTest {
    @InjectSpy KeycloakClients clients;
    @CacheName(KeycloakClients.CLIENT_UUID_CACHE) Cache clientUuidCache;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void deleteEvictsTheCachedUuid() {
        String clientId = "deleted-client";
        String uuid = testRealm.createClient(clientId);
        clients.findUuid(testRealm.name(), clientId); // resolved once, now cached
        assertEquals(Optional.of(uuid), cachedUuid(clientId), "findUuid should have cached the uuid");

        clients.delete(testRealm.name(), clientId, uuid);

        assertEquals(Optional.empty(), cachedUuid(clientId),
                "delete must evict the cache entry itself: a 404 on the next read would also clear it, "
                        + "but that is not what this test is checking");
    }

    // A cached uuid is only trusted after a cheap GET confirms the client still exists: a client removed outside
    // Orgkeep (the console, or a test's raw cleanup, as here) is read again instead of masking its absence forever.
    @Test
    void findUuidSelfHealsWhenTheClientIsRemovedOutsideOrgkeep() {
        String clientId = "removed-outside-orgkeep";
        String uuid = testRealm.createClient(clientId);
        assertEquals(Optional.of(uuid), clients.findUuid(testRealm.name(), clientId)); // resolved once, now cached

        // removed directly in Keycloak, bypassing KeycloakClients.delete: the cache is not evicted by that path
        testRealm.resource().clients().get(uuid).remove();

        assertTrue(clients.findUuid(testRealm.name(), clientId).isEmpty(),
                "a stale cache entry would still answer the removed uuid instead of reading again");
    }

    // The symmetric case: "not found" is never cached, so a client created right after, outside this method, is
    // not masked by a stale absence. Matters for the common "look up, create if absent" idiom used by the services.
    @Test
    void findUuidNeverCachesAnAbsence() {
        String clientId = "created-outside-orgkeep";
        assertTrue(clients.findUuid(testRealm.name(), clientId).isEmpty());

        // created directly in Keycloak, bypassing KeycloakClients.create: no cache entry to evict
        String uuid = testRealm.createClient(clientId);

        assertEquals(Optional.of(uuid), clients.findUuid(testRealm.name(), clientId),
                "a cached \"not found\" would mask the client just created outside this method");
    }

    // The cache hit is verified by clientId, not just existence: a client renamed in the console still exists (at
    // the same uuid), but a fresh search under its old clientId would no longer find it.
    @Test
    void findUuidSelfHealsWhenTheClientIsRenamedOutsideOrgkeep() {
        String oldClientId = "renamed-before";
        String uuid = testRealm.createClient(oldClientId);
        assertEquals(Optional.of(uuid), clients.findUuid(testRealm.name(), oldClientId)); // cached under oldClientId

        // renamed directly in Keycloak: same uuid, different clientId
        ClientRepresentation client = testRealm.resource().clients().get(uuid).toRepresentation();
        client.setClientId("renamed-after");
        testRealm.resource().clients().get(uuid).update(client);

        assertTrue(clients.findUuid(testRealm.name(), oldClientId).isEmpty(),
                "the cached uuid now belongs to a different clientId: a fresh search under the old one would find nothing");
    }

    // create() evicts a uuid it may have left stale in the cache (the clientId it is given could already be
    // cached under an older, now-deleted uuid): the next findUuid must see the newly created client right away.
    // Checked on the cache directly, like deleteEvictsTheCachedUuid: findUuid's self-heal would otherwise mask a
    // create() that never evicted anything.
    @Test
    void createEvictsAPreviouslyCachedUuid() {
        String clientId = "recreated-after-a-raw-delete";
        String oldUuid = testRealm.createClient(clientId);
        clients.findUuid(testRealm.name(), clientId); // resolved once, now cached
        assertEquals(Optional.of(oldUuid), cachedUuid(clientId));

        // removed directly in Keycloak, bypassing KeycloakClients.delete: the cache is not evicted by that path
        testRealm.resource().clients().get(oldUuid).remove();

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        String newUuid = clients.create(testRealm.name(), client).orElseThrow();

        // create() only evicts, it does not pre-populate: the stale entry must be gone, not replaced in place
        assertEquals(Optional.empty(), cachedUuid(clientId),
                "create() must evict the stale entry directly, not rely on the next findUuid's self-heal to notice it");
        assertEquals(Optional.of(newUuid), clients.findUuid(testRealm.name(), clientId),
                "the next findUuid, now a fresh miss, must find the client just created");
    }

    // The issue's point: an id already known is not searched again. A hit calls matches (the cheap GET that
    // verifies it), not a fresh search. Checking that matches returns true confirms the hit is really taken,
    // not just attempted; combined with the verify below (exactly one matches call, and nothing else on the
    // spy), that proves the hit answers without ever falling back to a fresh search.
    @Test
    void findUuidVerifiesACacheHitInsteadOfSearchingAgain() {
        String clientId = "verified-on-a-hit";
        String uuid = testRealm.createClient(clientId);
        assertEquals(Optional.of(uuid), clients.findUuid(testRealm.name(), clientId)); // miss: searches, then caches
        assertTrue(clients.matches(testRealm.name(), uuid, clientId));
        Mockito.clearInvocations(clients);

        Optional<String> second = clients.findUuid(testRealm.name(), clientId); // hit: verifies instead

        assertEquals(Optional.of(uuid), second);
        Mockito.verify(clients, Mockito.times(1)).matches(testRealm.name(), uuid, clientId);
    }

    private Optional<String> cachedUuid(String clientId) {
        CompletableFuture<String> cached = clientUuidCache.as(CaffeineCache.class)
                .getIfPresent(new CompositeCacheKey(testRealm.name(), clientId));
        return cached == null ? Optional.empty() : Optional.of(cached.join());
    }
}
