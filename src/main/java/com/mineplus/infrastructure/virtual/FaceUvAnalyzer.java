package com.mineplus.infrastructure.virtual;

/**
 * Analyzes a {@link BakedFace} UV window and derives the best <i>exactly
 * implementable</i> vanilla-server rendering of it.
 *
 * <p>Constraint: a {@code BlockDisplay} renders a complete vanilla block state; the
 * vanilla client offers no API to crop a texture to an arbitrary sub-window. Four tiers
 * are exactly reproducible:
 * <ol>
 *   <li><b>Tile</b> — wrapping windows (span &gt; 16px) render as a native-density grid,
 *       one display per texture repeat: zero stretching.</li>
 *   <li><b>Crop half</b> — half-texture windows render via slab-type block states whose
 *       texture is literally that half of the parent block's map.</li>
 *   <li><b>Texel</b> — with a resolvable PNG next to the model, the window is decomposed
 *       into per-pixel texels quantized to the vanilla flat-block palette and reconstructed
 *       as merged thin plates (see the {@code texel} package).</li>
 *   <li><b>Full</b> — every other window renders the complete texture (the honest
 *       fallback; sub-pixel cropping is impossible without a client resource pack).</li>
 * </ol>
 */
public final class FaceUvAnalyzer {

    private FaceUvAnalyzer() {
    }

    /** What the UV window asks for, and how to render it. */
    public record UvPlan(
            Strategy strategy,
            Half half,
            int orientationDegrees,
            int uTiles,
            int vTiles
    ) {

        public enum Strategy {
            /** Full texture on the face (default for non-half, non-wrapping windows). */
            FULL,
            /** Half-texture window rendered via slab crop with geometry compensation. */
            CROP_HALF,
            /** Wrapping window: N x M grid of full-texture tiles at native density. */
            TILE,
            /**
             * Texel surface baking: per-pixel palette-quantized reconstruction from the
             * face's texture image (only when texel baking is enabled and the PNG
             * resolves; degrades to this analyzer's other tiers otherwise).
             */
            TEXEL
        }

        public enum Half {
            NONE, TOP, BOTTOM, LEFT, RIGHT
        }

        public UvPlan(Strategy strategy, Half half, int orientationDegrees) {
            this(strategy, half, orientationDegrees, 1, 1);
        }
    }

    /** Derives the render plan for one face's UV window. */
    public static UvPlan analyze(BakedFace face) {
        if (face == null) {
            return new UvPlan(UvPlan.Strategy.FULL, UvPlan.Half.NONE, 0);
        }

        int rotation = face.rotation();
        if (face.isWrapping()) {
            return new UvPlan(UvPlan.Strategy.TILE, UvPlan.Half.NONE, rotation,
                    face.uTiles(), face.vTiles());
        }
        if (face.isHalfVertical()) {
            UvPlan.Half half = face.vCenter() < 0.5f ? UvPlan.Half.TOP : UvPlan.Half.BOTTOM;
            return new UvPlan(UvPlan.Strategy.CROP_HALF, half, rotation);
        }
        if (face.isHalfHorizontal()) {
            UvPlan.Half half = face.uCenter() < 0.5f ? UvPlan.Half.LEFT : UvPlan.Half.RIGHT;
            return new UvPlan(UvPlan.Strategy.CROP_HALF, half, rotation);
        }
        return new UvPlan(UvPlan.Strategy.FULL, UvPlan.Half.NONE, rotation);
    }

    /**
     * Derives the render plan for one face's UV window under a texel baking mode.
     *
     * <p>{@code OFF} (or an unresolvable texture image) keeps the legacy tiers.
     * {@code ON} forces TEXEL for every face with a resolvable image. {@code AUTO}
     * upgrades only {@link UvPlan.Strategy#FULL} faces — TILE and CROP_HALF are
     * already exact vanilla renderings, so baking them would only add entities.
     *
     * @param face                   the face to analyze
     * @param texelMode              resolved texel mode (never {@code null})
     * @param textureImageResolvable whether a decodable PNG exists for the face's texture
     */
    public static UvPlan analyze(BakedFace face, ModelMeta.TexelMode texelMode, boolean textureImageResolvable) {
        UvPlan existing = analyze(face);
        if (texelMode == null || texelMode == ModelMeta.TexelMode.OFF || !textureImageResolvable) {
            return existing;
        }
        if (texelMode == ModelMeta.TexelMode.ON
                || existing.strategy() == UvPlan.Strategy.FULL) {
            return new UvPlan(UvPlan.Strategy.TEXEL, UvPlan.Half.NONE, existing.orientationDegrees());
        }
        return existing;
    }

    /**
     * Face physical size in texture pixels for a cube face. Returns {@code [sizeU, sizeV]}.
     */
    public static float[] facePixelSize(CubeFace face, BakedCube cube) {
        float x = cube.scale().x * 16.0f;
        float y = cube.scale().y * 16.0f;
        float z = cube.scale().z * 16.0f;
        return switch (face) {
            case NORTH, SOUTH -> new float[]{x, y};
            case EAST, WEST -> new float[]{z, y};
            case UP, DOWN -> new float[]{x, z};
        };
    }
}
