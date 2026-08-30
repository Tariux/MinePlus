package com.mineplus.infrastructure.virtual.texel;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy 2D rectangle merging over a quantized texel grid (the same idea as vanilla
 * chunk greedy meshing): scan in row-major order, extend each seed cell to the widest
 * same-index run on its row, then downward while every row matches, emit the
 * rectangle, mark visited.
 *
 * <p>Properties: O(w·h) with tiny constants; rectangles never overlap and jointly
 * cover every non-transparent cell; the output order is deterministic (scan order);
 * merging operates on quantized palette indices, so a merged rectangle is guaranteed
 * to be a single solid material with no seam risk.
 */
public final class TexelMerge {

    private TexelMerge() {
    }

    /**
     * Merges a quantized grid into maximal rectangles.
     *
     * @param grid   flat grid in row-major order ({@code grid[row * width + col]});
     *               {@code -1} marks a transparent/no-plate cell
     * @param width  grid width in cells
     * @param height grid height in cells
     * @return merged rectangles in scan order
     */
    public static List<TexelSurfacePlan.Rect> merge(int[] grid, int width, int height) {
        List<TexelSurfacePlan.Rect> rectangles = new ArrayList<>();
        if (grid == null || width <= 0 || height <= 0 || grid.length < width * height) {
            return rectangles;
        }

        boolean[] visited = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int origin = y * width + x;
                if (visited[origin] || grid[origin] < 0) {
                    continue;
                }
                int color = grid[origin];

                int rectWidth = 1;
                while (x + rectWidth < width
                        && !visited[origin + rectWidth]
                        && grid[origin + rectWidth] == color) {
                    rectWidth++;
                }

                int rectHeight = 1;
                extendHeight:
                while (y + rectHeight < height) {
                    int rowStart = (y + rectHeight) * width + x;
                    for (int cx = 0; cx < rectWidth; cx++) {
                        if (visited[rowStart + cx] || grid[rowStart + cx] != color) {
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
                rectangles.add(new TexelSurfacePlan.Rect(x, y, rectWidth, rectHeight, color));
            }
        }
        return rectangles;
    }
}
