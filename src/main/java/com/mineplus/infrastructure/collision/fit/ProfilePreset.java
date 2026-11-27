package com.mineplus.infrastructure.collision.fit;

/** Named weight presets for the hybrid fitter. */
public enum ProfilePreset {
    /**
     * Furniture: never let a player walk through a seat or table top, so missing
     * volume costs the most. Ghosts are allowed for the sides.
     */
    FURNITURE(1.2, 3.0, 1.0, 0.2),
    /**
     * Narrow gate: extra volume must not block the opening, so extra solid is
     * punished hardest.
     */
    NARROW_GATE(4.0, 1.0, 1.0, 0.3),
    /** Walkable surfaces: step quality matters most. */
    WALKABLE_SURFACE(2.0, 2.5, 3.0, 0.1),
    /** Decorations that may be walked through but must be clickable. */
    DECORATIVE_PASSABLE(0.8, 0.8, 0.2, 0.4),
    /** General-purpose default. */
    BALANCED(1.5, 1.5, 1.0, 0.25);

    private final double alpha;
    private final double beta;
    private final double gamma;
    private final double delta;

    ProfilePreset(double alpha, double beta, double gamma, double delta) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.delta = delta;
    }

    public double alpha() {
        return alpha;
    }

    public double beta() {
        return beta;
    }

    public double gamma() {
        return gamma;
    }

    public double delta() {
        return delta;
    }

    /** Case-insensitive lookup, null-safe. */
    public static ProfilePreset fromKey(String key, ProfilePreset fallback) {
        if (key == null) {
            return fallback;
        }
        for (ProfilePreset preset : values()) {
            if (preset.name().equalsIgnoreCase(key.trim())) {
                return preset;
            }
        }
        return fallback;
    }
}
