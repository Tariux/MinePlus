package com.mineplus.infrastructure.virtual.voxel;

import com.mineplus.infrastructure.virtual.ModelMeta;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global voxel rendering settings (the {@code VOXEL_RENDERING} section of
 * {@code settings.mp.yml}); per-model {@code .meta.json} overrides take
 * precedence.
 *
 * <p>Defaults are chosen so a server that never touches the config sees zero
 * behavior change: {@code AUTO} only activates for non-animated, axis-aligned,
 * grid-snapped models with a resolvable texture PNG, and shipped models have
 * no adjacent PNGs.
 */
public record VoxelRenderingSettings(
        boolean enabled,
        ModelMeta.VoxelMode mode,
        int maxDisplays
) {

    public VoxelRenderingSettings {
        maxDisplays = Math.max(1, maxDisplays);
    }

    public static VoxelRenderingSettings defaults() {
        return new VoxelRenderingSettings(true, ModelMeta.VoxelMode.AUTO, 1024);
    }

    /** Effective mode for a model: global enable gates everything, then meta overrides. */
    public ModelMeta.VoxelMode effectiveMode(ModelMeta meta) {
        if (!enabled) {
            return ModelMeta.VoxelMode.OFF;
        }
        ModelMeta.VoxelMode resolved = meta != null && meta.voxelMode() != null
                ? meta.voxelMode() : mode;
        return resolved == null ? ModelMeta.VoxelMode.AUTO : resolved;
    }

    /** Effective whole-model display ceiling: meta overrides the global. */
    public int effectiveMaxDisplays(ModelMeta meta) {
        Integer resolved = meta != null ? meta.maxVoxelDisplays() : null;
        return resolved != null ? resolved : maxDisplays;
    }

    public static VoxelRenderingSettings parse(FileConfiguration yaml, VoxelRenderingSettings fallback) {
        if (!yaml.isConfigurationSection("VOXEL_RENDERING")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("VOXEL_RENDERING");
        VoxelRenderingSettings defaults = defaults();
        return new VoxelRenderingSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                ModelMeta.VoxelMode.fromKey(section.getString("MODE"), defaults.mode()),
                section.getInt("MAX_DISPLAYS", defaults.maxDisplays())
        );
    }
}
