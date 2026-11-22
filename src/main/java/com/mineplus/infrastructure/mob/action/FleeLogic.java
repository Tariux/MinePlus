package com.mineplus.infrastructure.mob.action;

/**
 * The pure decision core of {@link FleeAction}: which heading a fleeing mob
 * should take when it may be surrounded by more than one threat.
 *
 * <p>A naive flee runs along the vector away from the nearest threat, which
 * pushes a mob straight into a second one behind it. This samples headings
 * around the full circle and scores each by the <em>worst</em> predicted
 * distance gain across every threat (a maximin escape): running away from the
 * nearest threat while straight toward another scores worse than slipping out
 * through the gap between them, so a mob cornered between two players picks
 * the gap instead of the wall.
 *
 * <p>Pure math (no Bukkit types) — the action turns the chosen heading into a
 * standable world location.
 */
public final class FleeLogic {

    private FleeLogic() {
    }

    /**
     * Chooses the best flee heading.
     *
     * @param primaryAwayAngle  heading (radians) pointing directly away from the
     *                          nearest threat, in the world's X/Z plane
     * @param secondaryAngles   headings away from each additional threat
     * @param secondaryWeight   how much a secondary threat caps the score
     *                          (0 ignores them; 0.5 treats them as half-matters)
     * @param samples           how many headings to sample around the circle
     * @return the best heading in radians
     */
    public static double bestHeading(double primaryAwayAngle, double[] secondaryAngles,
                                     double secondaryWeight, int samples) {
        int n = Math.max(4, samples);
        double best = primaryAwayAngle;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double angle = (Math.PI * 2 * i) / n;
            double primaryGain = Math.cos(angle - primaryAwayAngle);
            double worst = primaryGain;
            if (secondaryAngles != null) {
                for (double secondary : secondaryAngles) {
                    worst = Math.min(worst, secondaryWeight * Math.cos(angle - secondary));
                }
            }
            // Tiny tie-break toward the primary so equal-gap headings lean home.
            double s = worst + 1.0E-6 * primaryGain;
            if (s > bestScore + 1.0E-9) {
                bestScore = s;
                best = angle;
            }
        }
        return best;
    }

    /** Heading (radians) of the unit vector {@code (x, z)}. */
    public static double headingOf(double x, double z) {
        return Math.atan2(z, x);
    }
}
