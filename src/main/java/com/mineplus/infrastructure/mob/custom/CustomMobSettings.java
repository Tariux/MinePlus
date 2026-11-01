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
 * @param renderInterpolationTicks client-side interpolation window in ticks;
 *                              passed to {@code setInterpolationDuration} so the
 *                              client smooths between pose updates; lower values
 *                              look smoother but cost more bandwidth. The rig
 *                              never eases over a window shorter than the pose
 *                              cadence, so raising {@code RENDER_INTERVAL_TICKS}
 *                              raises this floor with it
 * @param renderDistance        blocks beyond which a rig is released entirely:
 *                              its displays despawn, the base entity is shown,
 *                              and the rig is rebuilt when a viewer comes back
 *                              (hysteresis keeps a mob walking the border from
 *                              thrashing)
 * @param renderNearDistance    blocks inside which a rig is posed at full rate;
 *                              beyond it the pose cadence drops to a quarter
 * @param maxRiggedPerWorld     hard ceiling of rigged custom mobs per world
 * @param shadows               whether a rigged mob carries a ground shadow
 *                              plate (the mob's own vanilla shadow is not
 *                              rendered while its rig covers it)
 * @param removeUnknownOnLoad   whether a custom-tagged entity whose definition
 *                              is not registered is removed on chunk load
 */
public record CustomMobSettings(
        boolean enabled,
        boolean renderingEnabled,
        int renderIntervalTicks,
        int renderInterpolationTicks,
        int renderDistance,
        int renderNearDistance,
        int maxRiggedPerWorld,
        boolean shadows,
        boolean removeUnknownOnLoad
) {

    public CustomMobSettings {
        renderIntervalTicks = clamp(renderIntervalTicks, 1, 40);
        renderInterpolationTicks = clamp(renderInterpolationTicks, 1, 40);
        renderDistance = clamp(renderDistance, 8, 256);
        renderNearDistance = clamp(renderNearDistance, 4, renderDistance);
        maxRiggedPerWorld = clamp(maxRiggedPerWorld, 1, 4096);
    }

    public static CustomMobSettings defaults() {
        return new CustomMobSettings(true, true, 1, 1, 48, 20, 48, true, true);
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
                section.getInt("RENDER_INTERPOLATION_TICKS", defaults.renderInterpolationTicks()),
                section.getInt("RENDER_DISTANCE", defaults.renderDistance()),
                section.getInt("RENDER_NEAR_DISTANCE", defaults.renderNearDistance()),
                section.getInt("MAX_RIGGED_PER_WORLD", defaults.maxRiggedPerWorld()),
                section.getBoolean("SHADOWS", defaults.shadows()),
                section.getBoolean("REMOVE_UNKNOWN_ON_LOAD", defaults.removeUnknownOnLoad())
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
