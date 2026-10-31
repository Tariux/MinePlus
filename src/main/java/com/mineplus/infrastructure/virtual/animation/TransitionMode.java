package com.mineplus.infrastructure.virtual.animation;

import java.util.Locale;

/**
 * How automatic state selection switches clips when the derived state changes.
 */
public enum TransitionMode {
    /** Cut to the new state on the tick it is selected. */
    IMMEDIATE,
    /** Let the current automatic clip finish its pass before the new state starts. */
    AFTER_CURRENT;

    public static TransitionMode fromKey(String raw, TransitionMode fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "IMMEDIATE" -> IMMEDIATE;
            case "AFTER_CURRENT" -> AFTER_CURRENT;
            default -> fallback;
        };
    }
}
