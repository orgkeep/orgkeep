package org.orgkeep.repository.keycloak;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.GroupRepresentation;
import org.mockito.Mockito;
import org.orgkeep.fixtures.TestRealm;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Keycloak does when a sub group is created under a name already taken, against the Keycloak of Dev Services.
 * The idempotent creation of the groups of an assignment relies on it.
 * <p>
 * Also the caching of {@link KeycloakGroups#findId} (realm, path -> id, #56). Most of those tests check the final
 * answer of {@code findId}, which a correct self-heal (verify-on-hit, never cache an absence) would also produce
 * even if eviction on delete/create never ran: that alone would not catch an eviction that silently never
 * executes. The eviction tests inspect the cache directly instead (injecting its {@code Cache} bean, read as a
 * {@link CaffeineCache} for {@code getIfPresent}), and one test verifies, through a spy, that a hit calls
 * {@code matches} (package-private) rather than searching again.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakGroupsTest {
    @InjectSpy KeycloakGroups groups;
    @CacheName(KeycloakGroups.GROUP_ID_CACHE) Cache groupIdCache;

    private TestRealm testRealm;
    private String parentId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        parentId = groups.createTopLevel(testRealm.name(), "parent");
    }

    @AfterEach
    void cleanUp() {
        testRealm.resource().groups().group(parentId).remove();
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    // Pins the current behaviour: createChild goes through CreatedResponseUtil, which throws on Keycloak's 409
    @Test
    void createChildOnAnExistingNameThrowsWithTheConflictStatus() {
        groups.createChild(testRealm.name(), "/parent/child", parentId, "child");

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> groups.createChild(testRealm.name(), "/parent/child", parentId, "child"));

        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    void createChildIfAbsentCreatesThenAnswersEmptyOnAnExistingName() {
        var created = groups.createChildIfAbsent(testRealm.name(), "/parent/child", parentId, "child", Map.of());
        var again = groups.createChildIfAbsent(testRealm.name(), "/parent/child", parentId, "child", Map.of());

        assertTrue(created.isPresent());
        assertTrue(again.isEmpty());
        assertEquals(created, groups.findId(testRealm.name(), "/parent/child"));
    }

    @Test
    void deleteEvictsTheCachedId() {
        String path = "/parent/deleted";
        String groupId = groups.createChild(testRealm.name(), path, parentId, "deleted");
        groups.findId(testRealm.name(), path); // resolved once, now cached
        assertEquals(Optional.of(groupId), cachedId(path), "findId should have cached the id");

        groups.delete(testRealm.name(), path, groupId);

        assertEquals(Optional.empty(), cachedId(path),
                "delete must evict the cache entry itself: a 404 on the next read would also clear it, "
                        + "but that is not what this test is checking");
    }

    // A cached id is only trusted after a cheap GET confirms the group still exists: a group removed outside
    // Orgkeep (the console, or a test's raw cleanup, as here) is read again instead of masking its absence forever.
    @Test
    void findIdSelfHealsWhenTheGroupIsRemovedOutsideOrgkeep() {
        String path = "/parent/removed-outside-orgkeep";
        String groupId = groups.createChild(testRealm.name(), path, parentId, "removed-outside-orgkeep");
        assertEquals(Optional.of(groupId), groups.findId(testRealm.name(), path)); // resolved once, now cached

        // removed directly in Keycloak, bypassing KeycloakGroups.delete: the cache is not evicted by that path
        testRealm.resource().groups().group(groupId).remove();

        assertTrue(groups.findId(testRealm.name(), path).isEmpty(),
                "a stale cache entry would still answer the removed id instead of reading again");
    }

    // The symmetric case: "not found" is never cached, so a group created right after, outside this method, is
    // not masked by a stale absence. Matters for the common "look up, create if absent" idiom used by the services.
    @Test
    void findIdNeverCachesAnAbsence() {
        String path = "/parent/created-outside-orgkeep";
        assertTrue(groups.findId(testRealm.name(), path).isEmpty());

        // created directly in Keycloak, bypassing KeycloakGroups.createChild: no cache entry to evict
        GroupRepresentation group = new GroupRepresentation();
        group.setName("created-outside-orgkeep");
        try (Response response = testRealm.resource().groups().group(parentId).subGroup(group)) {
            assertEquals(201, response.getStatus());
        }
        String groupId = testRealm.resource().getGroupByPath(path).getId();

        assertEquals(Optional.of(groupId), groups.findId(testRealm.name(), path),
                "a cached \"not found\" would mask the group just created outside this method");
    }

    // The cache hit is verified by path, not just existence: a group renamed (or moved) in the console still
    // exists (at the same id), but a fresh search under its old path would no longer find it.
    @Test
    void findIdSelfHealsWhenTheGroupIsRenamedOutsideOrgkeep() {
        String oldPath = "/parent/renamed-before";
        String groupId = groups.createChild(testRealm.name(), oldPath, parentId, "renamed-before");
        assertEquals(Optional.of(groupId), groups.findId(testRealm.name(), oldPath)); // cached under oldPath

        // renamed directly in Keycloak: same id, different name (hence a different path)
        GroupRepresentation group = testRealm.resource().groups().group(groupId).toRepresentation();
        group.setName("renamed-after");
        testRealm.resource().groups().group(groupId).update(group);

        assertTrue(groups.findId(testRealm.name(), oldPath).isEmpty(),
                "the cached id now belongs to a different path: a fresh search under the old one would find nothing");
    }

    // createChild evicts an id it may have left stale in the cache (the path it is given could already be cached
    // under an older, now-deleted id). Checked on the cache directly, like deleteEvictsTheCachedId: findId's
    // self-heal would otherwise mask a createChild that never evicted anything.
    @Test
    void createChildEvictsAPreviouslyCachedId() {
        String path = "/parent/recreated-after-a-raw-delete";
        String oldId = groups.createChild(testRealm.name(), path, parentId, "recreated-after-a-raw-delete");
        groups.findId(testRealm.name(), path); // resolved once, now cached
        assertEquals(Optional.of(oldId), cachedId(path));

        // removed directly in Keycloak, bypassing KeycloakGroups.delete: the cache is not evicted by that path
        testRealm.resource().groups().group(oldId).remove();

        String newId = groups.createChild(testRealm.name(), path, parentId, "recreated-after-a-raw-delete");

        // createChild only evicts, it does not pre-populate: the stale entry must be gone, not replaced in place
        assertEquals(Optional.empty(), cachedId(path),
                "createChild must evict the stale entry directly, not rely on the next findId's self-heal to notice it");
        assertEquals(Optional.of(newId), groups.findId(testRealm.name(), path),
                "the next findId, now a fresh miss, must find the group just created");
    }

    // Same as createChildEvictsAPreviouslyCachedId, for createChildIfAbsent.
    @Test
    void createChildIfAbsentEvictsAPreviouslyCachedId() {
        String path = "/parent/recreated-if-absent-after-a-raw-delete";
        String name = "recreated-if-absent-after-a-raw-delete";
        String oldId = groups.createChildIfAbsent(testRealm.name(), path, parentId, name, Map.of()).orElseThrow();
        groups.findId(testRealm.name(), path); // resolved once, now cached
        assertEquals(Optional.of(oldId), cachedId(path));

        // removed directly in Keycloak, bypassing KeycloakGroups.delete: the cache is not evicted by that path
        testRealm.resource().groups().group(oldId).remove();

        String newId = groups.createChildIfAbsent(testRealm.name(), path, parentId, name, Map.of()).orElseThrow();

        assertEquals(Optional.empty(), cachedId(path),
                "createChildIfAbsent must evict the stale entry directly, not rely on the next findId's self-heal");
        assertEquals(Optional.of(newId), groups.findId(testRealm.name(), path),
                "the next findId, now a fresh miss, must find the group just created");
    }

    // Same as createChildEvictsAPreviouslyCachedId, for the top level group, which createChild does not cover
    // (createTopLevel has no parent, so it evicts the "/" + name path on its own). Not under "parent", so cleaned
    // up by hand: @AfterEach only removes "parent" and its sub tree.
    @Test
    void createTopLevelEvictsAPreviouslyCachedId() {
        String name = "recreated-top-level-after-a-raw-delete";
        String path = "/" + name;
        String oldId = groups.createTopLevel(testRealm.name(), name);
        groups.findId(testRealm.name(), path); // resolved once, now cached
        assertEquals(Optional.of(oldId), cachedId(path));

        // removed directly in Keycloak, bypassing KeycloakGroups.delete: the cache is not evicted by that path
        testRealm.resource().groups().group(oldId).remove();

        String newId = groups.createTopLevel(testRealm.name(), name);
        assertEquals(Optional.empty(), cachedId(path),
                "createTopLevel must evict the stale entry directly, not rely on the next findId's self-heal");
        assertEquals(Optional.of(newId), groups.findId(testRealm.name(), path),
                "the next findId, now a fresh miss, must find the group just created");
        testRealm.resource().groups().group(newId).remove();
    }

    // The issue's point: an id already known is not searched again. A hit calls matches (the cheap GET that
    // verifies it), not a fresh search. Checking that matches returns true confirms the hit is really taken,
    // not just attempted; combined with the verify below (exactly one matches call, and nothing else on the
    // spy), that proves the hit answers without ever falling back to a fresh search.
    @Test
    void findIdVerifiesACacheHitInsteadOfSearchingAgain() {
        String path = "/parent/verified-on-a-hit";
        String groupId = groups.createChild(testRealm.name(), path, parentId, "verified-on-a-hit");
        assertEquals(Optional.of(groupId), groups.findId(testRealm.name(), path)); // miss: searches, then caches
        assertTrue(groups.matches(testRealm.name(), groupId, path));
        Mockito.clearInvocations(groups);

        Optional<String> second = groups.findId(testRealm.name(), path); // hit: verifies instead

        assertEquals(Optional.of(groupId), second);
        // package-private: the test is in the same package
        Mockito.verify(groups, Mockito.times(1)).matches(testRealm.name(), groupId, path);
    }

    private Optional<String> cachedId(String path) {
        CompletableFuture<String> cached = groupIdCache.as(CaffeineCache.class)
                .getIfPresent(new CompositeCacheKey(testRealm.name(), path));
        return cached == null ? Optional.empty() : Optional.of(cached.join());
    }
}
