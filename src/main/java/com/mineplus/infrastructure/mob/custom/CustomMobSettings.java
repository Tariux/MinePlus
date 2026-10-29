package com.mineplus.infrastructure.mob.custom;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Runtime limits for the custom-mob subsystem ({@code CUSTOM_MOBS} section of
 * {@code settings.mp.yml}).
 *
 * <p>Custom mobs are ordinary vanilla base entities carrying a Mineplus
 * identity, so the expensive parts are the per-part display rigs. These
 * settings bound that cost: the rig update interval, the distance beyond which
 * a rig stops pushing transforms, and the number of rigged mobs per world. The
 * base entity itself is never more expensive than the vanilla mob it wraps.</p>
 *
 * @param enabled               master switch; {@code false} makes registration
 *                              and spawning no-ops (existing custom mobs keep
 *                              their behavior profiles but lose rigs on reload)
 * @param renderingEnabled      whether visual rigs are created at all; a custom
 *                              mob without a rig is visible as its vanilla base
 * @param renderIntervalTicks   server ticks between rig pose pushes
 * @param renderDistance        blocks beyond which a rig stops receiving pose
 *                              updates (the client also culls by view range)
 * @param maxRiggedPerWorld     hard ceiling of rigged custom mobs per world
 * @param removeUnknownOnLoad   whether a custom-tagged entity whose definition
 *                              is not registered is removed on chunk load
 */
public record CustomMobSettings(
        boolean enabled,
        boolean renderingEnabled,
        int renderIntervalTicks,
        int renderDistance,
        int maxRiggedPerWorld,
        boolean removeUnknownOnLoad
) {

    public CustomMobSettings {
        renderIntervalTicks = clamp(renderIntervalTicks, 1, 40);
        renderDistance = clamp(renderDistance, 8, 256);
        maxRiggedPerWorld = clamp(maxRiggedPerWorld, 1, 4096);
    }

    public static CustomMobSettings defaults() {
        return new CustomMobSettings(true, true, 2, 48, 96, true);
    }

    public static CustomMobSettings parse(FileConfiguration yaml, CustomMobSettings fallback) {
        if (yaml == null || !yaml.isConfigurationSection("CUSTOM_MOBS")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("CUSTOM_MOBS");
        CustomMobSettings defaults = defaults();
        return new CustomMobSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getBoolean("RENDERING", defaults.renderingEnabled()),
                section.getInt("RENDER_INTERVAL_TICKS", defaults.renderIntervalTicks()),
                section.getInt("RENDER_DISTANCE", defaults.renderDistance()),
                section.getInt("MAX_RIGGED_PER_WORLD", defaults.maxRiggedPerWorld()),
                section.getBoolean("REMOVE_UNKNOWN_ON_LOAD", defaults.removeUnknownOnLoad())
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
