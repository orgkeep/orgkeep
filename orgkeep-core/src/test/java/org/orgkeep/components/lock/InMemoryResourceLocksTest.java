package org.orgkeep.components.lock;

import org.junit.jupiter.api.Test;
import org.orgkeep.api.exceptions.ResourceBusyException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.orgkeep.components.lock.LockMode.EXCLUSIVE;
import static org.orgkeep.components.lock.LockMode.SHARED;

/**
 * Plain JUnit, no Quarkus: the lock registry only needs a wait time. A lock belongs to the thread that took it, so
 * "another caller" is a second thread; each call of {@link #onAnotherThread} runs on a fresh thread.
 */
class InMemoryResourceLocksTest {
    private static final String REALM = "acme";

    private final InMemoryResourceLocks locks = new InMemoryResourceLocks(Duration.ofMillis(300));

    @Test
    void sharedLocksOfTwoThreadsOnTheSameKeyCoexist() throws Exception {
        try (HeldLocks mine = locks.acquire(org("a", SHARED))) {
            assertTrue(onAnotherThread(() -> {
                try (HeldLocks theirs = locks.acquire(org("a", SHARED))) {
                    return true;
                }
            }));
        }
        assertEquals(0, locks.size());
    }

    @Test
    void exclusiveExcludesShared() throws Exception {
        try (HeldLocks mine = locks.acquire(org("a", EXCLUSIVE))) {
            assertBusyOnAnotherThread(() -> locks.acquire(org("a", SHARED)));
        }
    }

    @Test
    void sharedExcludesExclusive() throws Exception {
        try (HeldLocks mine = locks.acquire(org("a", SHARED))) {
            assertBusyOnAnotherThread(() -> locks.acquire(org("a", EXCLUSIVE)));
        }
    }

    @Test
    void differentKeysDoNotInterfere() throws Exception {
        try (HeldLocks mine = locks.acquire(org("a", EXCLUSIVE))) {
            assertTrue(onAnotherThread(() -> {
                try (HeldLocks theirs = locks.acquire(org("b", EXCLUSIVE))) {
                    return true;
                }
            }));
        }
    }

    @Test
    void acquireWaitsForARelease() throws Exception {
        HeldLocks mine = locks.acquire(org("a", EXCLUSIVE));
        CompletableFuture<Boolean> other = CompletableFuture.supplyAsync(() -> {
            try (HeldLocks theirs = locks.acquire(org("a", EXCLUSIVE))) {
                return true;
            }
        });
        Thread.sleep(80);
        mine.close();

        assertTrue(other.get(5, TimeUnit.SECONDS), "released within the max wait: the second caller gets the lock");
    }

    @Test
    void acquireGivesUpAfterTheMaxWait() throws Exception {
        try (HeldLocks mine = locks.acquire(org("a", EXCLUSIVE))) {
            long start = System.nanoTime();
            assertBusyOnAnotherThread(() -> locks.acquire(org("a", EXCLUSIVE)));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(waitedMillis >= 250, "waited about the max wait (300 ms), waited " + waitedMillis + " ms");
        }
    }

    @Test
    void tryAcquireDoesNotWait() throws Exception {
        InMemoryResourceLocks patient = new InMemoryResourceLocks(Duration.ofSeconds(30));
        try (HeldLocks mine = patient.acquire(org("a", EXCLUSIVE))) {
            long start = System.nanoTime();
            assertBusyOnAnotherThread(() -> patient.tryAcquire(org("a", EXCLUSIVE)));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(waitedMillis < 5_000, "failed at once, took " + waitedMillis + " ms");
        }
    }

    // all or nothing: the org is free, the user is held. The call fails and must not keep the org
    @Test
    void aFailedCallReleasesTheLocksItAlreadyTook() throws Exception {
        try (HeldLocks userHeld = locks.acquire(LockRequest.userById(REALM, "u1", EXCLUSIVE))) {
            assertBusyOnAnotherThread(() -> locks.tryAcquire(
                    LockRequest.userById(REALM, "u1", EXCLUSIVE), org("a", EXCLUSIVE)));
            // the requests are taken ORG first (canonical order), then USER: the org was taken, then released
            assertTrue(onAnotherThread(() -> {
                try (HeldLocks theirs = locks.tryAcquire(org("a", EXCLUSIVE))) {
                    return true;
                }
            }));
        }
        assertEquals(0, locks.size());
    }

    @Test
    void exclusiveUnderExclusiveOfTheSameThreadIsAllowed() {
        try (HeldLocks outer = locks.acquire(org("a", EXCLUSIVE));
             HeldLocks byAcquire = locks.acquire(org("a", EXCLUSIVE));
             HeldLocks byTryAcquire = locks.tryAcquire(org("a", EXCLUSIVE))) {
            assertEquals(1, locks.size());
        }
        assertEquals(0, locks.size());
    }

    @Test
    void sharedUnderExclusiveOfTheSameThreadIsAllowed() {
        try (HeldLocks outer = locks.acquire(org("a", EXCLUSIVE));
             HeldLocks inner = locks.acquire(org("a", SHARED))) {
            assertEquals(1, locks.size());
        }
        assertEquals(0, locks.size());
    }

    @Test
    void sharedUnderSharedOfTheSameThreadIsAllowed() {
        try (HeldLocks outer = locks.acquire(org("a", SHARED));
             HeldLocks inner = locks.acquire(org("a", SHARED))) {
            assertEquals(1, locks.size());
        }
        assertEquals(0, locks.size());
    }

    @Test
    void exclusiveUnderSharedOnlyIsAProgrammingError() {
        try (HeldLocks outer = locks.acquire(org("a", SHARED))) {
            assertThrows(IllegalStateException.class, () -> locks.acquire(org("a", EXCLUSIVE)));
            assertThrows(IllegalStateException.class, () -> locks.tryAcquire(org("a", EXCLUSIVE)));
        }
        assertEquals(0, locks.size(), "the failed attempts left no entry behind");
    }

    @Test
    void addTakesALockThatRanksAfterTheOnesHeld() {
        try (HeldLocks held = locks.acquire(org("a", SHARED))) {
            held.add(LockRequest.userByEmail(REALM, "Paul@Acme.test", EXCLUSIVE),
                    LockRequest.userById(REALM, "u1", EXCLUSIVE));
            assertEquals(3, locks.size());
        }
        assertEquals(0, locks.size());
    }

    @Test
    void addOfALowerRankIsAProgrammingError() {
        try (HeldLocks held = locks.acquire(LockRequest.team(REALM, "a", "t", EXCLUSIVE))) {
            assertThrows(IllegalStateException.class, () -> held.add(org("a", SHARED)), "ORG ranks before TEAM");
            // the same key again is not "after" either
            assertThrows(IllegalStateException.class,
                    () -> held.add(LockRequest.team(REALM, "a", "t", SHARED)));
        }
        try (HeldLocks held = locks.acquire(LockRequest.userById(REALM, "u1", EXCLUSIVE))) {
            assertThrows(IllegalStateException.class,
                    () -> held.add(LockRequest.userByEmail(REALM, "p@acme.test", EXCLUSIVE)),
                    "the email of a user ranks before its id");
        }
        assertEquals(0, locks.size());
    }

    // the rank of a user lock comes from the factory used, never from the key: an email may contain "/id:"
    @Test
    void userRankDoesNotDependOnTheContentOfTheKey() {
        LockRequest tricky = LockRequest.userByEmail("b", "x/id:y@acme.test", EXCLUSIVE);
        assertTrue(!tricky.byId());
        assertTrue(LockRequest.userById("a", "u1", EXCLUSIVE).byId());
        // the email ranks before the id, whatever the realm and the text of the key ("a/id:u1" < "b/email:x/id:...")
        assertTrue(LockRequest.RANK.compare(tricky, LockRequest.userById("a", "u1", EXCLUSIVE)) < 0);

        try (HeldLocks held = locks.acquire(LockRequest.userById("a", "u1", EXCLUSIVE))) {
            assertThrows(IllegalStateException.class, () -> held.add(tricky),
                    "an email ranks before an id even when its text contains /id:");
        }
        try (HeldLocks held = locks.acquire(tricky)) {
            held.add(LockRequest.userById("a", "u1", EXCLUSIVE));
        }
        assertEquals(0, locks.size());
    }

    // the lock of the user is held by another thread (the same thread would get it again, reentrantly)
    @Test
    void aBusyAddKeepsTheLocksTakenBefore() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // another thread holds the user
            HeldLocksBox box = new HeldLocksBox();
            executor.submit(() -> box.held = locks.acquire(LockRequest.userById(REALM, "u1", EXCLUSIVE))).get();

            try (HeldLocks held = locks.acquire(org("a", SHARED))) {
                assertThrows(ResourceBusyException.class,
                        () -> held.add(LockRequest.userById(REALM, "u1", EXCLUSIVE)));
                // the org taken before the add is still held by the call: an exclusive try elsewhere fails
                assertBusyOnAnotherThread(() -> locks.tryAcquire(org("a", EXCLUSIVE)));
            }
            executor.submit(() -> box.held.close()).get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, locks.size());
    }

    @Test
    void entriesAreRemovedAfterCloseEvenAfterAFailure() throws Exception {
        HeldLocks first = locks.acquire(org("a", EXCLUSIVE));
        assertEquals(1, locks.size());
        // "0" ranks before "a": it is taken, then "a" is busy and "0" is released
        assertBusyOnAnotherThread(() -> locks.tryAcquire(org("a", EXCLUSIVE), org("0", EXCLUSIVE)));
        assertEquals(1, locks.size(), "the failed call left nothing behind, not even the entry of 0");

        first.close();
        first.close();
        assertEquals(0, locks.size(), "removed at the last release, and a second close changes nothing");
    }

    // a lock added once the call is closed would never be released
    @Test
    void addAfterCloseIsRefused() {
        HeldLocks held = locks.acquire(org("a", SHARED));
        held.close();

        assertThrows(IllegalStateException.class, () -> held.add(LockRequest.userById(REALM, "u1", EXCLUSIVE)));
        assertEquals(0, locks.size(), "nothing was taken");
    }

    // the same key twice in one call is merged into the strongest mode, whatever the order of the arguments
    @Test
    void sameKeyTwiceInOneCallTakesTheStrongestMode() throws Exception {
        for (LockRequest[] requests : new LockRequest[][] {
                {org("a", SHARED), org("a", EXCLUSIVE)},
                {org("a", EXCLUSIVE), org("a", SHARED)}}) {
            try (HeldLocks held = locks.acquire(requests)) {
                assertEquals(1, locks.size());
                assertBusyOnAnotherThread(() -> locks.tryAcquire(org("a", SHARED)));
            }
            assertEquals(0, locks.size());
        }
    }

    private static LockRequest org(String org, LockMode mode) {
        return LockRequest.org(REALM, org, mode);
    }

    /** Runs the action on a fresh thread and returns its result. */
    private static <T> T onAnotherThread(Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(action).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertBusyOnAnotherThread(Callable<HeldLocks> action) throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> onAnotherThread(action));
        assertInstanceOf(ResourceBusyException.class, e.getCause());
    }

    private static final class HeldLocksBox {
        HeldLocks held;
    }
}
