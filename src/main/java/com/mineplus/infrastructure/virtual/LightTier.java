package com.mineplus.infrastructure.virtual;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Real {@code minecraft:light} emitted by a virtual model. The trick is the same
 * one the collision lattice uses: hidden vanilla blocks placed in air cells,
 * here light-emitting ones (sea lantern, glowstone, ...), managed by the same
 * spawn/rollback lifecycle so nothing is left behind on remove or abort.
 *
 * <p>The light level (0-15) maps to a light block tier at import time; cells are
 * taken from the same occupancy computation the collision lattice uses, so a
 * model lights exactly the space its geometry occupies.
 *
 * @param block   the light-emitting vanilla block constant for this tier
 * @param level   the block-light level the block emits (informational)
 * @param maxCells upper bound on light cells per model (perf guard)
 */
public record LightTier(Material block, int level, int maxCells) {

    /** Brightest practical room-lighting block per tier; level 0 means no light. */
    public static final LightTier OFF = new LightTier(null, 0, 0);
    public static final LightTier DIM = new LightTier(Material.SEA_LANTERN, 8, 64);
    public static final LightTier NORMAL = new LightTier(Material.GLOWSTONE, 15, 96);
    public static final LightTier BRIGHT = new LightTier(Material.SHROOMLIGHT, 15, 192);

    /** Parses a meta {@code "light"} key; unknown or blank values mean no light. */
    public static LightTier fromKey(String key) {
        if (key == null || key.isBlank()) {
            return OFF;
        }
        try {
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "dim" -> DIM;
                case "normal" -> NORMAL;
                case "bright" -> BRIGHT;
                default -> OFF;
            };
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }

    /** Places this tier's light blocks at the given world locations. */
    public int placeBlocks(List<Block> blocks) {
        if (block == null) {
            return 0;
        }
        int placed = 0;
        for (Block b : blocks) {
            if (placed >= maxCells) {
                break;
            }
            if (b.getType().isAir()) {
                b.setType(block);
                placed++;
            }
        }
        return placed;
    }
}
