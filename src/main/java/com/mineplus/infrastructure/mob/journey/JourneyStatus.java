package com.mineplus.infrastructure.mob.journey;

/** Outcome of one {@link JourneyRunner#advance} step. */
public enum JourneyStatus {

    /** Walking the current leg; nothing else for the caller to do. */
    WALKING,

    /** The destination was reached; the journey state is already gone. */
    ARRIVED,

    /** Outside the leash area — walking back to it instead of the destination. */
    RETURNING,

    /** The leg was re-issued around an obstacle after a no-progress strike. */
    DETOURING,

    /** No path to the current waypoint at all (water, a cliff, an unloaded area). */
    BLOCKED,

    /** Gave up: repeated no-progress strikes; the caller decides the fallback. */
    STUCK,

    /** The runner is at capacity; retry on the next evaluation. */
    NO_CAPACITY
}
