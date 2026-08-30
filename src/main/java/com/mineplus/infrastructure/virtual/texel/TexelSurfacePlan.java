package com.mineplus.infrastructure.virtual.texel;

import java.util.List;

/**
 * Immutable per-face bake output: the face's effective texel grid plus the merged
 * rectangles covering it. One {@link Rect} becomes one thin {@code BlockDisplay}
 * plate at emission time.
 *
 * <p>Rectangles are grid-cell ranges: {@code (x, y)} is the cell column/row in the
 * window's coordinate frame (row 0 = top of the UV window, i.e. the top of the face),
 * and {@code paletteIndex} addresses {@link TexelPalette}. Transparent cells never
 * appear in any rectangle, so the base display shows through there — which is why the
 * plan also carries the face's <i>dominant</i> palette entry (largest total cell
 * area, deterministic tie-break to the lower index): the emitter colors the cube's
 * base display with it, so cutout holes reveal a matching local color instead of the
 * filename-resolver fallback.
 *
 * <p>Plans are recomputed on reload, never persisted.
 */
public record TexelSurfacePlan(
        int gridWidth,
        int gridHeight,
        List<Rect> plates,
        int dominantPaletteIndex,
        int dominantArea
) {

    public TexelSurfacePlan {
        plates = plates == null ? List.of() : List.copyOf(plates);
    }

    public int plateCount() {
        return plates.size();
    }

    /**
     * One merged run of same-quantized-color texels: grid cell rectangle plus the
     * palette entry all its texels quantized to.
     */
    public record Rect(int x, int y, int width, int height, int paletteIndex) {
    }
}
