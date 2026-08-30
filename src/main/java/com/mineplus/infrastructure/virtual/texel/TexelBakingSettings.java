package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.ModelMeta;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global texel surface baking settings (the {@code TEXEL_BAKING} section of
 * {@code settings.mp.yml}); per-model {@code .meta.json} overrides take precedence.
 *
 * <p>Defaults are chosen so a server that never touches the config sees zero behavior
 * change: {@code AUTO} only activates for FULL-strategy faces with a resolvable PNG,
 * and shipped models have no adjacent PNGs.
 */
public record TexelBakingSettings(
        boolean enabled,
        ModelMeta.TexelMode mode,
        ModelMeta.TexelDetail detail,
        int maxPlatesPerFace,
        int maxPlatesPerInstance,
        int maxGridEdge
) {

    public TexelBakingSettings {
        maxPlatesPerFace = Math.max(1, maxPlatesPerFace);
        maxPlatesPerInstance = Math.max(1, maxPlatesPerInstance);
        maxGridEdge = Math.max(1, maxGridEdge);
    }

    public static TexelBakingSettings defaults() {
        return new TexelBakingSettings(
                true,
                ModelMeta.TexelMode.AUTO,
                ModelMeta.TexelDetail.FACE,
                96,
                150,
                64
        );
    }

    /** Effective mode for a model: global enable gates everything, then meta overrides. */
    public ModelMeta.TexelMode effectiveMode(ModelMeta meta) {
        if (!enabled) {
            return ModelMeta.TexelMode.OFF;
        }
        ModelMeta.TexelMode resolved = meta != null && meta.texelMode() != null
                ? meta.texelMode() : mode;
        return resolved == null ? ModelMeta.TexelMode.AUTO : resolved;
    }

    /** Effective sampling detail for a model: meta overrides the global. */
    public ModelMeta.TexelDetail effectiveDetail(ModelMeta meta) {
        ModelMeta.TexelDetail resolved = meta != null && meta.texelDetail() != null
                ? meta.texelDetail() : detail;
        return resolved == null ? ModelMeta.TexelDetail.FACE : resolved;
    }

    /** Effective per-face plate ceiling: meta overrides the global. */
    public int effectiveMaxPlatesPerFace(ModelMeta meta) {
        Integer resolved = meta != null ? meta.maxTexelPlatesPerFace() : null;
        return resolved != null ? resolved : maxPlatesPerFace;
    }

    /** Effective per-instance plate budget: meta overrides the global. */
    public int effectiveMaxPlatesPerInstance(ModelMeta meta) {
        Integer resolved = meta != null ? meta.maxTexelPlatesPerInstance() : null;
        return resolved != null ? resolved : maxPlatesPerInstance;
    }

    public static TexelBakingSettings parse(FileConfiguration yaml, TexelBakingSettings fallback) {
        if (!yaml.isConfigurationSection("TEXEL_BAKING")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("TEXEL_BAKING");
        TexelBakingSettings defaults = defaults();
        return new TexelBakingSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                ModelMeta.TexelMode.fromKey(section.getString("MODE"), defaults.mode()),
                ModelMeta.TexelDetail.fromKey(section.getString("DETAIL"), defaults.detail()),
                section.getInt("MAX_PLATES_PER_FACE", defaults.maxPlatesPerFace()),
                section.getInt("MAX_PLATES_PER_INSTANCE", defaults.maxPlatesPerInstance()),
                section.getInt("MAX_GRID_EDGE", defaults.maxGridEdge())
        );
    }
}
