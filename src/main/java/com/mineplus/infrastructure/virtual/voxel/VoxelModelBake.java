package com.mineplus.infrastructure.virtual.voxel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-model voxel bake output: the merged 3D voxel runs consumed at spawn time
 * plus the diagnostics surfaced by {@code /mineplus model info}.
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
     * One merged 3D display run: model-space origin plus 3D extents (X, Y, Z),
     * sharing a single palette entry and light emission.
     */
    public record VoxelRun(
            float x,
            float y,
            float z,
            int lengthX,
            int heightY,
            int widthZ,
            int paletteIndex,
            int lightEmission
    ) {
        /** Backward compatibility constructor (heightY = 1). */
        public VoxelRun(float x, float y, float z, int lengthX, int widthZ, int paletteIndex, int lightEmission) {
            this(x, y, z, lengthX, 1, widthZ, paletteIndex, lightEmission);
        }
    }
}