package com.mineplus.infrastructure.virtual.texel;

import java.util.ArrayList;
import java.util.List;

/**
 * Uniform-area plate reduction: finds large flat regions of a baked face grid
 * and collapses each into a single {@link TexelSurfacePlan.Rect}, then defers
 * the remaining cells to {@link TexelMerge}.
 *
 * <p>The win over {@link TexelMerge} alone is two-fold: it coalesces regions
 * whose cells quantized to <i>near-identical</i> stretchable palette entries
 * (within an Oklab tolerance), and it collapses large regions of <b>exactly
 * equal</b> non-stretchable entries (powders, terracottas, stones) that the
 * merge deliberately leaves 1&times;1. Because every cell in a region maps to the
 * same flat palette block, a single stretched plate is visually equivalent to
 * the many plates it replaces; the tradeoff is that per-block grain is not
 * tiled, which the minimum-size gate keeps negligible and configurable.</p>
 *
 * <p>Coalescing never crosses transparent or occluded cells (both are negative
 * sentinels), and only stretchable seeds absorb non-identical neighbors — a
 * non-stretchable seed coalesces exclusively exact matches, so its color is
 * never altered. The pass is deterministic and allocation-bounded to one
 * boolean mask plus one grid copy.</p>
 */
public final class TexelUniformCoalescer {

    private TexelUniformCoalescer() {
    }

    /**
     * Produces the final plate list for a face: uniform-region rects first, then
     * the greedy merge of everything not coalesced.
     *
     * @param grid          the baked palette-index grid ({@code >= 0}) with negative
     *                      transparent/occluded sentinels
     * @param width         grid width in texels
     * @param height        grid height in texels
     * @param minSize       minimum region area (in texels) to coalesce; below it regions
     *                      are left to {@link TexelMerge}
     * @param oklabThreshold perceptual tolerance for stretchable regions
     * @param mergeTolerance tolerance handed to {@link TexelMerge} for the remainder
     */
    public static List<TexelSurfacePlan.Rect> mergeWithUniformAreas(
            int[] grid, int width, int height, int minSize, float oklabThreshold, float mergeTolerance) {
        if (grid == null || width <= 0 || height <= 0 || grid.length < width * height || minSize <= 1) {
            return TexelMerge.merge(grid, width, height, mergeTolerance);
        }

        List<TexelSurfacePlan.Rect> uniform = new ArrayList<>();
        boolean[] consumed = new boolean[width * height];
        boolean anyConsumed = false;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int origin = y * width + x;
                if (consumed[origin] || grid[origin] < 0) {
                    continue;
                }
                int seed = grid[origin];

                int regionWidth = 1;
                while (x + regionWidth < width) {
                    int index = origin + regionWidth;
                    if (consumed[index] || !matches(seed, grid[index], oklabThreshold)) {
                        break;
                    }
                    regionWidth++;
                }

                int regionHeight = 1;
                extendHeight:
                while (y + regionHeight < height) {
                    int rowStart = (y + regionHeight) * width + x;
                    for (int dx = 0; dx < regionWidth; dx++) {
                        int index = rowStart + dx;
                        if (consumed[index] || !matches(seed, grid[index], oklabThreshold)) {
                            break extendHeight;
                        }
                    }
                    regionHeight++;
                }

                int area = regionWidth * regionHeight;
                if (area < minSize) {
                    continue;
                }

                for (int dy = 0; dy < regionHeight; dy++) {
                    int rowStart = (y + dy) * width + x;
                    for (int dx = 0; dx < regionWidth; dx++) {
                        consumed[rowStart + dx] = true;
                    }
                }
                anyConsumed = true;
                uniform.add(new TexelSurfacePlan.Rect(x, y, regionWidth, regionHeight, seed));
            }
        }

        if (!anyConsumed) {
            return TexelMerge.merge(grid, width, height, mergeTolerance);
        }

        int[] remainder = grid.clone();
        for (int i = 0; i < consumed.length; i++) {
            if (consumed[i]) {
                remainder[i] = -1;
            }
        }
        uniform.addAll(TexelMerge.merge(remainder, width, height, mergeTolerance));
        return uniform;
    }

    /**
     * Region membership: exact matches always qualify; near-identical palette
     * entries qualify only when the seed is stretchable (so a grained
     * non-stretchable seed never absorbs a different color).
     */
    private static boolean matches(int seed, int candidate, float oklabThreshold) {
        if (candidate < 0) {
            return false;
        }
        if (candidate == seed) {
            return true;
        }
        if (!TexelPalette.isStretchable(seed)) {
            return false;
        }
        if (!TexelPalette.isStretchable(candidate)) {
            return false;
        }
        return TexelPalette.oklabDistance(seed, candidate) <= oklabThreshold;
    }
}
