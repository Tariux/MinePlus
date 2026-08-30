package com.mineplus.infrastructure.virtual.texel;

import java.util.List;

/**
 * Immutable per-face bake output: the face's effective texel grid plus the merged
 * rectangles covering it. One {@link Rect} becomes one thin {@code BlockDisplay}
 * plate at emission time.
 *
 * <p>Rectangles are grid-cell ranges: {@code (x, y)} is the cell column/row in the
 * window's coordinate frame (row 0 = top of the UV window, i.e. the top of the face),
 * and {@code paletteIndex} addresses {@link TexelPalette}. Transparent cells and
 * <i>occluded</i> cells (texels whose plate would sit inside another cube's solid —
 * the buried midsection of a band-wrapped body, a cork's hidden base) never appear
 * in any rectangle, so no entity is spawned inside other geometry: every emitted
 * plate occupies its own distinct, visible space. Occluded cell counts are tracked
 * per face for diagnostics.
 *
 * <p>The plan also carries the face's <i>dominant</i> palette entry (largest total
 * cell area, deterministic tie-break to the lower index): the emitter colors the
 * cube's base display with it, so cutout holes reveal a matching local color instead
 * of the filename-resolver fallback.
 *
 * <p>{@code cutoutCells} counts the <i>genuine</i> transparent survivors (rim-reached
 * post-infill): the see-through holes of layered cutout art — a transparent glass
 * band whose label shows through. A cube with any cutout face must not render its
 * full-cube base display, which would z-block the layered content behind the holes
 * (see {@code DisplayEmitter}).
 *
 * <p>Plans are recomputed on reload, never persisted.
 */
public record TexelSurfacePlan(
        int gridWidth,
        int gridHeight,
        List<Rect> plates,
        int dominantPaletteIndex,
        int dominantArea,
        int occludedCells,
        int cutoutCells
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
