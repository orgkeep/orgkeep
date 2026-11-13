package org.orgkeep.components.lock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.config.OrgkeepConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link ResourceLocks} for one instance of the core: one {@link ReentrantReadWriteLock} per key (shared = read lock,
 * exclusive = write lock), kept in a map only while a call holds or waits for it.
 * <p>
 * Why counted entries and not a fixed array of locks indexed by hash: two keys falling in the same slot within one call
 * (shared then exclusive) would always fail. The map is bounded by the calls in progress: the count of users of an
 * entry goes up before the try and down at the release, and the entry is removed at zero, both under
 * {@code compute}.
 * <p>
 * A read-write lock belongs to the thread that took it: the locks are taken and released on the thread of the request,
 * which the try-with-resources of the service guarantees. Reentrancy, for the same thread and the same key: exclusive
 * under exclusive and shared under exclusive (a downgrade) are allowed; exclusive under shared only is a programming
 * error ({@link IllegalStateException}), not a busy resource.
 */
@Slf4j
@ApplicationScoped
public class InMemoryResourceLocks implements ResourceLocks {
    private final Duration maxWait;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Inject
    public InMemoryResourceLocks(OrgkeepConfig config) {
        this(config.locks().maxWait());
    }

    /** For the unit tests, which choose a short wait. */
    InMemoryResourceLocks(Duration maxWait) {
        this.maxWait = maxWait;
    }

    @Override
    public HeldLocks acquire(LockRequest... requests) {
        return take(maxWait, requests);
    }

    @Override
    public HeldLocks tryAcquire(LockRequest... requests) {
        return take(Duration.ZERO, requests);
    }

    private HeldLocks take(Duration wait, LockRequest... requests) {
        Held held = new Held(wait);
        held.lockAll(sorted(requests));
        return held;
    }

    private static List<LockRequest> sorted(LockRequest... requests) {
        return Arrays.stream(requests).sorted(LockRequest.RANK).toList();
    }

    /** Package-private for the tests, which check that an entry is removed at the last release. */
    int size() {
        return entries.size();
    }

    /** The lock of a key and the number of calls holding or waiting for it. */
    private static final class Entry {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        int users;
    }

    private Entry retain(String key) {
        return entries.compute(key, (k, entry) -> {
            Entry current = entry == null ? new Entry() : entry;
            current.users++;
            return current;
        });
    }

    private void release(String key) {
        entries.compute(key, (k, entry) -> {
            if (entry == null || --entry.users <= 0) {
                return null;
            }
            return entry;
        });
    }

    private record Taken(String key, Entry entry, LockRequest request) {
        Lock lock() {
            return request.mode() == LockMode.EXCLUSIVE ? entry.lock.writeLock() : entry.lock.readLock();
        }
    }

    /** The locks of one call, released by close, on the same thread. */
    private final class Held implements HeldLocks {
        private final Duration wait;
        private final List<Taken> taken = new ArrayList<>();
        private LockRequest last;
        private boolean closed;

        Held(Duration wait) {
            this.wait = wait;
        }

        @Override
        public HeldLocks add(LockRequest... requests) {
            List<LockRequest> sorted = sorted(requests);
            if (last != null && !sorted.isEmpty() && LockRequest.RANK.compare(sorted.getFirst(), last) <= 0) {
                throw new IllegalStateException("A lock added late must rank after the locks already held: "
                        + sorted.getFirst().registryKey() + " after " + last.registryKey());
            }
            lockAll(sorted);
            return this;
        }

        /** All or nothing for this call: if one lock fails, the ones just taken here are released. */
        void lockAll(List<LockRequest> requests) {
            int before = taken.size();
            long deadline = System.nanoTime() + wait.toNanos();
            try {
                for (LockRequest request : requests) {
                    lockOne(request, deadline);
                }
            } catch (RuntimeException e) {
                releaseFrom(before);
                throw e;
            }
        }

        private void lockOne(LockRequest request, long deadline) {
            String key = request.registryKey();
            Entry entry = retain(key);
            boolean locked = false;
            try {
                locked = tryLock(entry, request.mode(), deadline);
            } finally {
                if (!locked) {
                    release(key);
                }
            }
            if (!locked) {
                // no name in the exception (it ends in a response); the log has it
                log.debug("Lock {} busy", key);
                throw new ResourceBusyException();
            }
            taken.add(new Taken(key, entry, request));
            last = request;
        }

        private boolean tryLock(Entry entry, LockMode mode, long deadline) {
            if (mode == LockMode.EXCLUSIVE && !entry.lock.isWriteLockedByCurrentThread()
                    && entry.lock.getReadHoldCount() > 0) {
                throw new IllegalStateException("A shared lock cannot be upgraded to exclusive: ask for the "
                        + "exclusive mode from the start");
            }
            Lock lock = mode == LockMode.EXCLUSIVE ? entry.lock.writeLock() : entry.lock.readLock();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return lock.tryLock();
            }
            try {
                return lock.tryLock(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseFrom(0);
        }

        /** Releases, last taken first, the locks from this index on. */
        private void releaseFrom(int index) {
            for (int i = taken.size() - 1; i >= index; i--) {
                Taken lock = taken.remove(i);
                lock.lock().unlock();
                release(lock.key());
            }
            last = taken.isEmpty() ? null : taken.getLast().request();
        }
    }
}
