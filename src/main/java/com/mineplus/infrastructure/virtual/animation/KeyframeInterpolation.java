package com.mineplus.infrastructure.virtual.animation;

import java.util.Locale;

/**
 * Blockbench keyframe interpolation modes. Catmull-Rom and Bezier fall back to
 * linear sampling: exact spline evaluation needs Blockbench's handle parameters
 * which the animation timeline does not export into {@code .bbmodel} keyframes.
 */
public enum KeyframeInterpolation {

    LINEAR,
    STEP,
    CATMULLROM,
    BEZIER,
    EASE;

    public boolean smooth() {
        return this != STEP;
    }

    public static KeyframeInterpolation fromKey(String key, KeyframeInterpolation fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "linear" -> LINEAR;
            case "step" -> STEP;
            case "catmullrom" -> CATMULLROM;
            case "bezier" -> BEZIER;
            case "ease" -> EASE;
            default -> fallback;
        };
    }
}
