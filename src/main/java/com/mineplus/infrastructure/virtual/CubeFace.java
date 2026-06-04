package com.mineplus.infrastructure.virtual;

import java.util.Locale;

public enum CubeFace {
    NORTH,
    SOUTH,
    EAST,
    WEST,
    UP,
    DOWN;

    public static CubeFace fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return CubeFace.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
