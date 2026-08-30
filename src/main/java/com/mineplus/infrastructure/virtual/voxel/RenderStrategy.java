package com.mineplus.infrastructure.virtual.voxel;

/**
 * The rendering representation a model will be spawned with. The three legacy
 * strategies are the existing pipeline's internal modes (chosen by the existing
 * settings and {@code FaceUvAnalyzer} tiers); {@link #VOXEL} replaces the
 * whole-model emission with the 1x1x1 voxel reconstruction built by
 * {@link VoxelSurfaceBaker}.
 *
 * <p>Selection is deterministic and separated from the implementations
 * ({@link RenderStrategySelector}); only {@code VOXEL} changes spawn behavior —
 * every other strategy keeps the existing rendering path byte-for-byte.
 */
public enum RenderStrategy {
    /** One display per cube, primary texture's material (per-face plates off). */
    CLASSIC,
    /** Per-face material plates over the majority-material base display. */
    FACE,
    /** Legacy pipeline with texel surface baking engaged on at least one face. */
    TEXEL,
    /** Texel-aware 1x1x1 voxel reconstruction of the whole model. */
    VOXEL
}
