package org.orgkeep.components.organisation;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per organisation, held while it is enabled or disabled (OrgServiceImpl.setEnabled), while a member joins
 * it and the org is checked again (OrgMemberServiceImpl.add, the owner in OrgServiceImpl.create), and while a removed
 * member leaves its list of disabled members (OrgMemberServiceImpl.remove).
 * <p>
 * Why: setEnabled reads the organisation, then writes it back whole. The admin API replaces the whole representation
 * and has no compare-and-set, so two calls at the same time can overwrite each other's {@code enabled} and
 * {@code disabled-members}: for instance the org left enabled while a member stays disabled for good, no longer listed.
 * With the lock, the second call starts once the first one is done.
 * <p>
 * Limits:
 * <ul>
 *     <li>only within one instance of the core: several instances do not share it. OrgServiceImpl reads the org again
 *     before dropping the disabled members, which covers the worst case there;</li>
 *     <li>the map keeps one small lock per organisation ever enabled or disabled, never removed (removing one safely
 *     would need a count of its users): a few dozen bytes per org;</li>
 *     <li>reentrant: a call made by the same thread while it holds the lock goes through.</li>
 * </ul>
 */
@ApplicationScoped
public class OrgLocks {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Runs the action alone for this organisation: another call for the same org waits until it is done. No timeout:
     * a second call waits as long as the first one runs (bounded by the timeouts of the Keycloak admin client).
     */
    public void run(String realm, String orgName, Runnable action) {
        call(realm, orgName, () -> {
            action.run();
            return null;
        });
    }

    /** Same as {@link #run}, for an action with a result. */
    public <T> T call(String realm, String orgName, Supplier<T> action) {
        ReentrantLock lock = lockOf(realm, orgName);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Package-private for the tests, which check that a second call waits. */
    ReentrantLock lockOf(String realm, String orgName) {
        // realm and org names are slugs, never with a "/"
        return locks.computeIfAbsent(realm + "/" + orgName, key -> new ReentrantLock());
    }
}
