package com.mineplus.infrastructure.virtual.animation;

import java.util.Locale;

/** Blockbench {@code animations[].loop} semantics. */
public enum LoopMode {

    /** Play once, then remove the controller and return affected bones to rest. */
    ONCE,
    /** Wrap time at the clip length and play forever. */
    LOOP,
    /** Play once and hold the final frame. */
    HOLD;

    public static LoopMode fromKey(String key, LoopMode fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return LoopMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
