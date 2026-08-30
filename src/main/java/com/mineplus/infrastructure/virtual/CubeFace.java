package com.mineplus.infrastructure.virtual;

import java.util.Locale;

public enum CubeFace {
    NORTH,
    SOUTH,
    EAST,
    WEST,
    UP,
    DOWN;

    /** Normal axis index: X=0, Y=1, Z=2. */
    public int normalAxis() {
        return switch (this) {
            case EAST, WEST -> 0;
            case UP, DOWN -> 1;
            default -> 2;
        };
    }

    /**
     * Axis index of the face's U coordinate in the vanilla box-unwrap convention
     * (U is horizontal in the texture for side faces), consistent with
     * {@code FaceUvAnalyzer.facePixelSize}: north/south faces unwrap along X,
     * east/west faces along Z, top/bottom along X. Distinct from
     * {@link #normalAxis()} for every face.
     */
    public int uAxis() {
        return switch (this) {
            case EAST, WEST -> 2;
            default -> 0;
        };
    }

    /** Axis index of the face's V coordinate (vertical in the texture, except on UP/DOWN). */
    public int vAxis() {
        return switch (this) {
            case UP, DOWN -> 2;
            default -> 1;
        };
    }

    /** True when the outward normal points along the positive axis direction. */
    public boolean positiveNormal() {
        return switch (this) {
            case SOUTH, EAST, UP -> true;
            default -> false;
        };
    }

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
