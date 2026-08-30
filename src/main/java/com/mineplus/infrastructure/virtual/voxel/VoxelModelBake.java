package com.mineplus.infrastructure.virtual.voxel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-model voxel bake output: the merged voxel runs consumed at spawn time
 * plus the diagnostics surfaced by {@code /mineplus model info}.
 *
 * <p>One {@link VoxelRun} becomes one {@code BlockDisplay}: a maximal
 * XZ-plane rectangle of voxels (greedy-merged per Y level) sharing one palette
 * entry and one light emission. Run origins are in <b>model space</b> (the same
 * coordinate frame as cube translations, lattice-shifted for the model's origin
 * mode), so spawn-side transform composition treats them exactly like cube
 * displays. Rectangle merging — rather than runs along +X only — collapses
 * large same-color floors and walls from O(edge) displays to O(color-change)
 * displays, the dominant entity-count reduction for blocky world-scale models.
 *
 * <p>When {@link #strategy()} is not {@link RenderStrategy#VOXEL}, the run list
 * is empty and the existing rendering pipeline renders the model unchanged.
 * Plans are recomputed on reload, never persisted.
 */
public record VoxelModelBake(
        RenderStrategy strategy,
        String rationale,
        List<VoxelRun> runs,
        int occupiedVoxels,
        int surfaceVoxels,
        int culledInteriorVoxels,
        long bakeTimeNanos,
        Map<Integer, Integer> paletteUsage
) {

    public VoxelModelBake {
        runs = runs == null ? List.of() : List.copyOf(runs);
        paletteUsage = paletteUsage == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(paletteUsage));
    }

    public boolean voxelRender() {
        return strategy == RenderStrategy.VOXEL;
    }

    /**
     * One merged display run: model-space origin plus XZ extents, with the palette
     * entry and light emission shared by all its voxels. Extents of 1 render a
     * single voxel exactly.
     */
    public record VoxelRun(
            float x,
            float y,
            float z,
            int lengthX,
            int widthZ,
            int paletteIndex,
            int lightEmission
    ) {
    }
}
