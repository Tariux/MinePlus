package com.mineplus.infrastructure.virtual;

/**
 * One baked cube face: the UV window into the texture map, its in-plane rotation, and
 * the referenced texture.
 *
 * <p>UV coordinates are in texture pixels, Blockbench convention: {@code (u1,v1)} is the
 * top-left and {@code (u2,v2)} the bottom-right of the window; the texture-space V axis
 * points down. A full 16x16 texture is {@code (0,0,16,16)}.
 */
public record BakedFace(
        float u1,
        float v1,
        float u2,
        float v2,
        int rotation,
        String textureReference,
        String textureName
) {

    public BakedFace {
        rotation = ((rotation % 360) + 360) % 360;
    }

    private float uSpan() {
        return Math.abs(u2 - u1) / 16.0f;
    }

    private float vSpan() {
        return Math.abs(v2 - v1) / 16.0f;
    }

    /** True when the window is a horizontal half (left/right) of the texture. */
    boolean isHalfHorizontal() {
        return Math.abs(uSpan() - 0.5f) < 1.0e-3f && vSpan() > 1.0f - 1.0e-3f;
    }

    /** True when the window is a vertical half (top/bottom) of the texture. */
    boolean isHalfVertical() {
        return Math.abs(vSpan() - 0.5f) < 1.0e-3f && uSpan() > 1.0f - 1.0e-3f;
    }

    /** True when the UV window wraps past the texture edge (span > 16px on an axis). */
    boolean isWrapping() {
        return uSpan() > 1.0f + 1.0e-3f || vSpan() > 1.0f + 1.0e-3f;
    }

    /** Integer tile count along U when the window wraps (>= 1). */
    int uTiles() {
        return Math.max(1, (int) Math.round(Math.abs(u2 - u1) / 16.0f));
    }

    /** Integer tile count along V when the window wraps (>= 1). */
    int vTiles() {
        return Math.max(1, (int) Math.round(Math.abs(v2 - v1) / 16.0f));
    }

    /** Center U of the window (0..1 texture units). */
    float uCenter() {
        return (u1 + u2) / 32.0f;
    }

    /** Center V of the window (0..1 texture units). */
    float vCenter() {
        return (v1 + v2) / 32.0f;
    }
}
