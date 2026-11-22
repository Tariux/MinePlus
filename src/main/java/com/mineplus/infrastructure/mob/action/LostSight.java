package com.mineplus.infrastructure.mob.action;

/**
 * The pure decision core of the last-seen search in {@link AttackNearestAction}:
 * what a hunter should do when its prey breaks line of sight.
 *
 * <p>Real hunters do not path to a position they cannot see — they return to
 * where the prey was last visible, and give up if they arrive and find nothing
 * or if the trail goes cold. This class weighs exactly those facts; the action
 * supplies them each evaluation.
 */
public final class LostSight {

    /** What the hunter should do about its unseen prey. */
    public enum Verdict {
        /** Still within the trail's age: walk to the last-seen spot. */
        PURSUE,
        /** Reached the last-seen spot and found nothing: the trail is dead. */
        ARRIVED,
        /** The trail expired without arrival: too much time has passed. */
        EXPIRED
    }

    private LostSight() {
    }

    /**
     * @param ageTicks              ticks since the prey was last visible
     * @param distanceSqToLastSeen  squared distance from the hunter to the
     *                              last-seen spot
     * @param arrivedDistanceSq     how close counts as "arrived" (squared)
     * @param graceTicks            how long a trail stays warm
     */
    public static Verdict decide(long ageTicks, double distanceSqToLastSeen,
                                 double arrivedDistanceSq, long graceTicks) {
        if (ageTicks > graceTicks) {
            return Verdict.EXPIRED;
        }
        if (distanceSqToLastSeen <= arrivedDistanceSq) {
            return Verdict.ARRIVED;
        }
        return Verdict.PURSUE;
    }
}
