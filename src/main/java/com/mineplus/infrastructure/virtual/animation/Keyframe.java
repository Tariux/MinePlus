package com.mineplus.infrastructure.virtual.animation;

/**
 * One animation keyframe. Values keep their Blockbench channel units:
 * rotation in degrees (delta from rest), position in pixels, scale as a
 * multiplier (1 = rest).
 */
public record Keyframe(
        float time,
        float x,
        float y,
        float z,
        KeyframeInterpolation interpolation
) {
}
