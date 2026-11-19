package com.mineplus.infrastructure.mob.action;

/**
 * The pure decision core of {@link GuardAnchorAction}: nearest qualifying
 * threat within the anchor radius. Free of Bukkit types so the pipeline tests
 * exercise it directly; the action glues entity scans around it.
 */
public final class GuardLogic {

    private GuardLogic() {
    }

    /**
     * The index of the nearest qualifying candidate within a horizontal
     * {@code radius} of the anchor, or -1. {@code count} bounds the scan;
     * non-qualifying candidates are skipped even when nearest.
     */
    public static int pickThreatIndex(double anchorX, double anchorZ, double radius,
            double[] xs, double[] zs, boolean[] qualifies, int count) {
        double radiusSq = radius * radius;
        int best = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (!qualifies[i]) {
                continue;
            }
            double distSq = (xs[i] - anchorX) * (xs[i] - anchorX) + (zs[i] - anchorZ) * (zs[i] - anchorZ);
            if (distSq <= radiusSq && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }
}
