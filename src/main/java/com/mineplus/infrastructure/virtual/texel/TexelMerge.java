package com.mineplus.infrastructure.virtual.texel;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy 2D rectangle merging with strict stretchability rules:
 * only pure-flat concretes (and snow) may merge into stretched rectangles.
 * Concrete powders, terracottas and all detailed materials are strictly 1x1.
 */
public final class TexelMerge {

    private TexelMerge() {
    }

    public static List<TexelSurfacePlan.Rect> merge(int[] grid, int width, int height) {
        return merge(grid, width, height, 0.0f);
    }

    public static List<TexelSurfacePlan.Rect> merge(int[] grid, int width, int height, float maxOklabDistance) {
        List<TexelSurfacePlan.Rect> rectangles = new ArrayList<>();
        if (grid == null || width <= 0 || height <= 0 || grid.length < width * height) {
            return rectangles;
        }

        boolean[] visited = new boolean[width * height];
        boolean strict = maxOklabDistance <= 0.0f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int origin = y * width + x;
                if (visited[origin] || grid[origin] < 0) {
                    continue;
                }
                int seedColor = grid[origin];

                // Grained materials (powders, terracotta, mud, stones) MUST NOT be stretched!
                if (!TexelPalette.isStretchable(seedColor)) {
                    rectangles.add(new TexelSurfacePlan.Rect(x, y, 1, 1, seedColor));
                    visited[origin] = true;
                    continue;
                }

                // Smooth concrete blocks merge greedily
                int rectWidth = 1;
                while (x + rectWidth < width && !visited[origin + rectWidth]) {
                    int neighbor = grid[origin + rectWidth];
                    if (!isMatch(seedColor, neighbor, strict, maxOklabDistance)) {
                        break;
                    }
                    rectWidth++;
                }

                int rectHeight = 1;
                extendHeight:
                while (y + rectHeight < height) {
                    int rowStart = (y + rectHeight) * width + x;
                    for (int cx = 0; cx < rectWidth; cx++) {
                        int cell = rowStart + cx;
                        if (visited[cell] || !isMatch(seedColor, grid[cell], strict, maxOklabDistance)) {
                            break extendHeight;
                        }
                    }
                    rectHeight++;
                }

                for (int ry = 0; ry < rectHeight; ry++) {
                    int rowStart = (y + ry) * width + x;
                    for (int rx = 0; rx < rectWidth; rx++) {
                        visited[rowStart + rx] = true;
                    }
                }
                rectangles.add(new TexelSurfacePlan.Rect(x, y, rectWidth, rectHeight, seedColor));
            }
        }
        return rectangles;
    }

    private static boolean isMatch(int seed, int candidate, boolean strict, float maxDist) {
        if (candidate < 0) return false;
        // If candidate is a non-stretchable material, do not absorb it into a stretched rect
        if (!TexelPalette.isStretchable(candidate)) return false;
        if (seed == candidate) return true;
        if (strict) return false;
        return TexelPalette.oklabDistance(seed, candidate) <= maxDist;
    }
}