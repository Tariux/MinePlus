package com.mineplus.infrastructure.virtual.rig;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Runtime limits for bone rigs ({@code RIGS} section of {@code settings.mp.yml}).
 *
 * <p>A rig replaces one display per cube with one display per bone, so its cost
 * is bounded by the model's bone count rather than its geometry. These settings
 * bound the remaining variables: whether the pack bone axis renders at all,
 * whether hitbox-only bones are spawned, and the display ceiling one rig may
 * never exceed.</p>
 *
 * @param enabled                master switch for the pack bone-rig axis
 * @param hitboxBones            spawn invisible displays for {@code hitbox}-tagged bones
 * @param interpolationTicks     client-side interpolation window for pose pushes
 * @param maxDisplaysPerInstance hard ceiling of display bones per rig
 */
public record RigSettings(
        boolean enabled,
        boolean hitboxBones,
        int interpolationTicks,
        int maxDisplaysPerInstance
) {

    public RigSettings {
        interpolationTicks = Math.max(1, Math.min(20, interpolationTicks));
        maxDisplaysPerInstance = Math.max(1, Math.min(4096, maxDisplaysPerInstance));
    }

    /** The new backend defaults off: unconfigured servers keep the virtual path. */
    public static RigSettings defaults() {
        return new RigSettings(false, false, 2, 64);
    }

    /** Same limits with the master switch forced; the mob rig's own flag gates it instead. */
    public RigSettings withEnabled(boolean value) {
        return new RigSettings(value, hitboxBones, interpolationTicks, maxDisplaysPerInstance);
    }

    public static RigSettings parse(FileConfiguration yaml, RigSettings fallback) {
        if (yaml == null || !yaml.isConfigurationSection("RIGS")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("RIGS");
        RigSettings defaults = defaults();
        return new RigSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getBoolean("HITBOX_BONES", defaults.hitboxBones()),
                section.getInt("INTERPOLATION_TICKS", defaults.interpolationTicks()),
                section.getInt("MAX_DISPLAYS_PER_INSTANCE", defaults.maxDisplaysPerInstance())
        );
    }
}
