package com.mineplus.infrastructure.collision.atlas;

/** Mechanical properties of a candidate state that the fitter cares about. */
public enum ShapeFlag {
    /** Falls when unsupported. */
    GRAVITY,
    /** Breaks or updates when a neighbour changes (torches, plants, rails). */
    PHYSICS_SENSITIVE,
    /** Emits or reacts to redstone power. */
    REDSTONE_ACTIVE,
    /** Backed by a block entity (hoppers, lecterns, skulls, ...). */
    TILE_ENTITY,
    /** Emits light, which matters for decorative models. */
    EMITS_LIGHT,
    /** Fully occludes the neighbour faces / blocks light. */
    OCCLUDING,
    /** The state has no collision at all. */
    NO_COLLISION,
    /** Invisible without a resource pack (barrier, light, structure void). */
    NATURALLY_INVISIBLE,
    /** Only usable as a ghost (never as a real world block). */
    GHOST_ONLY,
    /** Can be clicked or hit (button, lever, candle, ...). */
    INTERACTABLE;

    /** Stable bit mask used by the binary cache; order must not change. */
    public static int toBits(Iterable<ShapeFlag> flags) {
        int bits = 0;
        for (ShapeFlag flag : flags) {
            bits |= 1 << flag.ordinal();
        }
        return bits;
    }

    /** Decodes a bit mask produced by {@link #toBits(Iterable)}. */
    public static java.util.Set<ShapeFlag> fromBits(int bits) {
        java.util.EnumSet<ShapeFlag> flags = java.util.EnumSet.noneOf(ShapeFlag.class);
        for (ShapeFlag flag : values()) {
            if ((bits & (1 << flag.ordinal())) != 0) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
