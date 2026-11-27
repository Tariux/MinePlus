package com.mineplus.infrastructure.collision.fit;

/**
 * One fitted block cell.
 *
 * @param x               block X in model space
 * @param y               block Y in model space
 * @param z               block Z in model space
 * @param state           chosen block state, null for interaction-only cells
 * @param backend         how the cell is realised
 * @param score           fit score (higher is better)
 * @param extraBits       solid sub-voxels where the model is empty
 * @param missingBits     model sub-voxels the choice leaves empty
 * @param topSurface      top of the chosen shape in block units, 0 when none
 * @param standingSurface whether players stand on this cell's top
 * @param reason          why the planner chose this backend
 */
public record CellChoice(
        int x,
        int y,
        int z,
        String state,
        BackendType backend,
        double score,
        int extraBits,
        int missingBits,
        double topSurface,
        boolean standingSurface,
        String reason) {

    public CellChoice {
        backend = backend == null ? BackendType.NONE : backend;
        reason = reason == null ? "" : reason;
        topSurface = Math.max(0, Math.min(1, topSurface));
    }

    public CellChoice withBackend(BackendType newBackend, String newReason) {
        return new CellChoice(x, y, z, state, newBackend, score, extraBits, missingBits,
                topSurface, standingSurface, newReason);
    }

    public CellChoice withScore(double newScore, int newExtra, int newMissing,
            double newTop, boolean standing) {
        return new CellChoice(x, y, z, state, backend, newScore, newExtra, newMissing, newTop,
                standing, reason);
    }

    /** Total mismatch in sub-voxels. */
    public int errorBits() {
        return extraBits + missingBits;
    }

    /** Three-element coordinates. */
    public int[] position() {
        return new int[] {x, y, z};
    }

    /** Stable textual key used by golden tests. */
    public String signature() {
        return x + "," + y + "," + z + '=' + (state == null ? "-" : state)
                + '@' + backend + "[" + extraBits + '/' + missingBits + ']';
    }
}
