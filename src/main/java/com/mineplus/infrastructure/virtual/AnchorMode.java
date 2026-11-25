package com.mineplus.infrastructure.virtual;

/**
 * How a spawned model is anchored in the world: the tiered strategy that picks
 * collision and interaction hooks to match the model's footprint.
 *
 * <ul>
 *   <li>{@link #LATTICE} — machines and multiblocks: a barrier lattice per
 *       occupancy cell (today's behavior), hooked by lattice break events.</li>
 *   <li>{@link #FRAME} — small items (cup, decor): no barrier blocks at all;
 *       one invisible {@code Interaction} entity sized from the model's
 *       bounding box carries the hooks, and an external visual (pack display)
 *       can ride it as a passenger so it can never drift from its anchor.</li>
 *   <li>{@link #INTERACTION_ONLY} — walk-over decor: like FRAME but with a
 *       full-block interaction hitbox for easier clicking.</li>
 * </ul>
 */
public enum AnchorMode {
    LATTICE,
    FRAME,
    INTERACTION_ONLY;

    /** Resolves the anchor mode for a meta {@code anchorMode} value. */
    public static AnchorMode fromKey(String key, AnchorMode fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return AnchorMode.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
