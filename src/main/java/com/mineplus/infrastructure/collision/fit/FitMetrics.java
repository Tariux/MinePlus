package com.mineplus.infrastructure.collision.fit;

import java.util.Locale;

/**
 * Measured quality of one fit.
 *
 * @param cells              cells considered (excluding pruned)
 * @param blocks             real blocks the plan will place
 * @param ghosts             client-only blocks
 * @param entities           shulkers, interactions and plates
 * @param prunedInterior     cells skipped because they were fully enclosed
 * @param extraVolume        solid sub-voxels where the model is empty
 * @param missingVolume      model sub-voxels left empty
 * @param targetVolume       solid sub-voxels in the occupancy mask
 * @param maxLedge           largest walk-relevant step between adjacent tops
 * @param ledgeViolations    adjacent pairs outside the profile's step rules
 */
public record FitMetrics(
        int cells,
        int blocks,
        int ghosts,
        int entities,
        int prunedInterior,
        long extraVolume,
        long missingVolume,
        long targetVolume,
        double maxLedge,
        int ledgeViolations) {

    /** Mismatch as a fraction of the model volume, 0 when the model is empty. */
    public double errorRatio() {
        if (targetVolume <= 0) {
            return extraVolume > 0 ? 1.0 : 0.0;
        }
        return (extraVolume + missingVolume) / (double) targetVolume;
    }

    /** One-line summary for {@code fit report}. */
    public String summary() {
        return String.format(Locale.ROOT,
                "%d cells, %d blocks, %d ghosts, %d entities, %d pruned; error %.1f%%"
                        + " (extra %d, missing %d of %d); max ledge %.3f, violations %d",
                cells, blocks, ghosts, entities, prunedInterior, errorRatio() * 100.0,
                extraVolume, missingVolume, targetVolume, maxLedge, ledgeViolations);
    }
}
