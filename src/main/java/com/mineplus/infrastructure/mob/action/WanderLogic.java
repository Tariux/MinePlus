package com.mineplus.infrastructure.mob.action;

import java.util.random.RandomGenerator;

/**
 * The pure decision core of {@link WanderAction}: destination sampling, the
 * radius / leg / standable rejection rules and the rest window between trips.
 * Free of Bukkit types so the pipeline tests exercise it directly; the action
 * glues entities and world reads around it.
 */
public final class WanderLogic {

    /** The number of candidate destinations sampled per pick. */
    public static final int CANDIDATE_BUDGET = 6;

    /** Resolves the standable ground y for {@code (x, z)} near {@code nearY}, or NaN. */
    public interface GroundYFinder {
        double groundY(double x, double z, double nearY);
    }

    private WanderLogic() {
    }

    /**
     * Samples up to {@link #CANDIDATE_BUDGET} destinations inside the home
     * radius, rejecting candidates beyond a horizontal {@code maxLeg} of the
     * current position or without standable ground. Square-root distance
     * sampling keeps the pick uniform over the disc. Returns {@code null}
     * when no candidate survives.
     */
    public static double[] pickDestination(double homeX, double homeY, double homeZ,
            double currentX, double currentY, double currentZ,
            double radius, double maxLeg, RandomGenerator random, GroundYFinder ground) {
        double maxLegSq = maxLeg * maxLeg;
        for (int attempt = 0; attempt < CANDIDATE_BUDGET; attempt++) {
            double angle = random.nextDouble() * (Math.PI * 2.0);
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double x = homeX + Math.cos(angle) * distance;
            double z = homeZ + Math.sin(angle) * distance;
            double legSq = (x - currentX) * (x - currentX) + (z - currentZ) * (z - currentZ);
            if (legSq > maxLegSq) {
                continue;
            }
            double y = ground.groundY(x, z, currentY);
            if (Double.isNaN(y)) {
                continue;
            }
            return new double[]{x, y, z};
        }
        return null;
    }

    /** The rest window between two wander trips, as plain nanoseconds. */
    public static final class Dwell {
        private long restUntil = Long.MIN_VALUE;

        /** True when the rest window has passed (always true before the first rest). */
        public boolean ready(long nowNanos) {
            return nowNanos >= restUntil;
        }

        /** Starts one rest window, uniformly distributed in {@code [min, max]}. */
        public void beginRest(long nowNanos, long minNanos, long maxNanos, RandomGenerator random) {
            long span = minNanos + (long) (random.nextDouble() * Math.max(0.0, maxNanos - minNanos));
            restUntil = nowNanos + span;
        }
    }
}
