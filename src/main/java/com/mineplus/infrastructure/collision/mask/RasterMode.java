package com.mineplus.infrastructure.collision.mask;

/** How a collision box becomes sub-voxels. */
public enum RasterMode {
    /**
     * A cell is solid when any part of it overlaps the box. Never misses a
     * surface, at the cost of slightly fat shapes.
     */
    CONSERVATIVE,
    /** A cell is solid when its centre is inside the box. */
    CENTERED,
    /** A cell is solid when at least half of it is inside the box. */
    MAJORITY;

    public static RasterMode fromKey(String key, RasterMode fallback) {
        if (key == null) {
            return fallback;
        }
        for (RasterMode mode : values()) {
            if (mode.name().equalsIgnoreCase(key.trim())) {
                return mode;
            }
        }
        return fallback;
    }
}
