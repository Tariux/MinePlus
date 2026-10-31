package com.mineplus.infrastructure.fx.unit;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Composed visual-unit budgets (the {@code VISUAL_UNITS} section of
 * {@code settings.mp.yml}).
 *
 * <p>Disabled by default: with {@code ENABLED: false} the service registers no
 * task, and {@code spawn}/{@code impact} return before allocating anything.</p>
 *
 * @param enabled          master switch; false = no task, no allocation
 * @param maxActive        ceiling of live units
 * @param maxSpawnPerTick  ceiling of units admitted per tick
 * @param maxMsPerTick     wall-clock budget of one advance pass (0 = no bound)
 */
public record VisualUnitSettings(boolean enabled, int maxActive, int maxSpawnPerTick, int maxMsPerTick) {

    public VisualUnitSettings {
        maxActive = Math.max(1, maxActive);
        maxSpawnPerTick = Math.max(1, maxSpawnPerTick);
        maxMsPerTick = Math.max(0, maxMsPerTick);
    }

    public static VisualUnitSettings defaults() {
        return new VisualUnitSettings(false, 64, 16, 1);
    }

    public static VisualUnitSettings parse(FileConfiguration yaml, VisualUnitSettings fallback) {
        VisualUnitSettings defaults = fallback == null ? defaults() : fallback;
        if (!yaml.isConfigurationSection("VISUAL_UNITS")) {
            return defaults;
        }
        var section = yaml.getConfigurationSection("VISUAL_UNITS");
        return new VisualUnitSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getInt("MAX_ACTIVE", defaults.maxActive()),
                section.getInt("MAX_SPAWN_PER_TICK", defaults.maxSpawnPerTick()),
                section.getInt("MAX_MS_PER_TICK", defaults.maxMsPerTick())
        );
    }
}
