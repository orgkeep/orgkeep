package org.orgkeep.components.lock;

/**
 * SHARED locks are compatible with each other (several short writes in the same organisation); EXCLUSIVE excludes
 * every other holder, shared or not (a heavy operation on the organisation).
 */
public enum LockMode {
    SHARED,
    EXCLUSIVE
}
