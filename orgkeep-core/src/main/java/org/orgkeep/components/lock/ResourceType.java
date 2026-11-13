package org.orgkeep.components.lock;

/**
 * What a lock protects. The declaration order is the acquisition order: a call that needs several locks takes them
 * ORG, then TEAM, then USER, so two calls can never wait for each other.
 */
public enum ResourceType {
    ORG,
    TEAM,
    USER
}
