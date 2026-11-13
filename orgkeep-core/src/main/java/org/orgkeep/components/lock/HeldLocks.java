package org.orgkeep.components.lock;

/**
 * The locks a call holds, released by {@link #close()}: used in a try-with-resources, on the thread that took them
 * (a lock belongs to its thread).
 */
public interface HeldLocks extends AutoCloseable {
    /**
     * Takes more locks, once their keys are known (the id of a user found by its email), with the wait policy of the
     * call that returned this object. Each must rank after every lock already held, otherwise this is a programming
     * error ({@link IllegalStateException}). If one is busy, the ones taken by this call are released and
     * {@link org.orgkeep.api.exceptions.ResourceBusyException} is thrown; the earlier ones stay held until close.
     */
    HeldLocks add(LockRequest... requests);

    /** Releases every lock, once: a second call does nothing. No checked exception. */
    @Override
    void close();
}
