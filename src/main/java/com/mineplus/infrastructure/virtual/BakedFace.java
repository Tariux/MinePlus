package com.mineplus.infrastructure.virtual;

/**
 * One baked cube face: the UV window into the texture map, its in-plane rotation, and
 * the referenced texture.
 *
 * <p>UV coordinates are in texture pixels, Blockbench convention: {@code (u1,v1)} is the
 * top-left and {@code (u2,v2)} the bottom-right of the window; the texture-space V axis
 * points down. A full 16x16 texture is {@code (0,0,16,16)}.</p>
 *
 * <p>Window heuristics (wrapping, halves, tile counts) are resolution-aware: a model
 * authored at 32x32 expresses full-texture windows as {@code (0,0,32,32)}, so every
 * span comparison takes the model's texture resolution. The parameterless variants
 * assume the vanilla 16x16 base.</p>
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

    private float uSpan(int textureWidth) {
        return Math.abs(u2 - u1) / (float) textureWidth;
    }

    private float vSpan(int textureHeight) {
        return Math.abs(v2 - v1) / (float) textureHeight;
    }

    /** True when the window is a horizontal half (left/right) of a 16x16 texture. */
    boolean isHalfHorizontal() {
        return isHalfHorizontal(16, 16);
    }

    /** True when the window is a horizontal half (left/right) of the texture. */
    boolean isHalfHorizontal(int textureWidth, int textureHeight) {
        return Math.abs(uSpan(textureWidth) - 0.5f) < 1.0e-3f && vSpan(textureHeight) > 1.0f - 1.0e-3f;
    }

    /** True when the window is a vertical half (top/bottom) of a 16x16 texture. */
    boolean isHalfVertical() {
        return isHalfVertical(16, 16);
    }

    /** True when the window is a vertical half (top/bottom) of the texture. */
    boolean isHalfVertical(int textureWidth, int textureHeight) {
        return Math.abs(vSpan(textureHeight) - 0.5f) < 1.0e-3f && uSpan(textureWidth) > 1.0f - 1.0e-3f;
    }

    /** True when the UV window wraps past a 16x16 texture edge (span > 16px on an axis). */
    boolean isWrapping() {
        return isWrapping(16, 16);
    }

    /** True when the UV window wraps past the texture edge (span exceeds it on an axis). */
    boolean isWrapping(int textureWidth, int textureHeight) {
        return uSpan(textureWidth) > 1.0f + 1.0e-3f || vSpan(textureHeight) > 1.0f + 1.0e-3f;
    }

    /** Integer tile count along U when a 16x16 window wraps (>= 1). */
    int uTiles() {
        return uTiles(16);
    }

    /** Integer tile count along U when the window wraps (>= 1). */
    int uTiles(int textureWidth) {
        return Math.max(1, (int) Math.round(Math.abs(u2 - u1) / (float) textureWidth));
    }

    /** Integer tile count along V when a 16x16 window wraps (>= 1). */
    int vTiles() {
        return vTiles(16);
    }

    /** Integer tile count along V when the window wraps (>= 1). */
    int vTiles(int textureHeight) {
        return Math.max(1, (int) Math.round(Math.abs(v2 - v1) / (float) textureHeight));
    }

    /** Center U of the window (0..1) against a 16x16 texture. */
    float uCenter() {
        return uCenter(16);
    }

    /** Center U of the window (0..1 of the texture width). */
    float uCenter(int textureWidth) {
        return (u1 + u2) / (2.0f * textureWidth);
    }

    /** Center V of the window (0..1) against a 16x16 texture. */
    float vCenter() {
        return vCenter(16);
    }

    /** Center V of the window (0..1 of the texture height). */
    float vCenter(int textureHeight) {
        return (v1 + v2) / (2.0f * textureHeight);
    }
}
