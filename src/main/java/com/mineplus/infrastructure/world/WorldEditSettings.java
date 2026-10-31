package com.mineplus.infrastructure.world;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Deferred world-edit queue limits (the {@code WORLD_EDITS} section of
 * {@code settings.mp.yml}).
 *
 * <p>Disabled by default: {@code enqueue} then applies each edit immediately
 * (the historical behavior) and no task exists.</p>
 *
 * @param enabled           master switch; false = edits apply on enqueue
 * @param maxMsPerTick      wall-clock budget of one drain pass, in milliseconds
 *                          (0 = no time bound)
 * @param maxBlocksPerTick  hard ceiling of blocks applied by one drain pass
 */
public record WorldEditSettings(boolean enabled, int maxMsPerTick, int maxBlocksPerTick) {

    public WorldEditSettings {
        maxMsPerTick = Math.max(0, maxMsPerTick);
        maxBlocksPerTick = Math.max(1, maxBlocksPerTick);
    }

    public static WorldEditSettings defaults() {
        return new WorldEditSettings(false, 2, 256);
    }

    public static WorldEditSettings parse(FileConfiguration yaml, WorldEditSettings fallback) {
        WorldEditSettings defaults = fallback == null ? defaults() : fallback;
        if (!yaml.isConfigurationSection("WORLD_EDITS")) {
            return defaults;
        }
        var section = yaml.getConfigurationSection("WORLD_EDITS");
        return new WorldEditSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getInt("MAX_MS_PER_TICK", defaults.maxMsPerTick()),
                section.getInt("MAX_BLOCKS_PER_TICK", defaults.maxBlocksPerTick())
        );
    }
}
