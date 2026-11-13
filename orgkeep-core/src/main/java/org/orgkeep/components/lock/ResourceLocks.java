package org.orgkeep.components.lock;

/**
 * Shared / exclusive locks per resource, to refuse a change while another one is in progress on the same resource
 * instead of letting two of them overwrite each other (the Keycloak admin API has no compare-and-set).
 * <p>
 * The requests of a call are taken together, in the canonical order, all or nothing. A call never goes from shared to
 * exclusive on a key it holds ({@link IllegalStateException}): it asks for the strongest mode from the start.
 * <p>
 * The only implementation, {@link InMemoryResourceLocks}, is valid for one instance of the core.
 */
public interface ResourceLocks {
    /**
     * Short write: waits up to {@code orgkeep.locks.max-wait} for the locks.
     *
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a lock is still held by someone else after that
     */
    HeldLocks acquire(LockRequest... requests);

    /**
     * Heavy operation on an organisation: a single, immediate try.
     *
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a lock is held by someone else
     */
    HeldLocks tryAcquire(LockRequest... requests);
}
