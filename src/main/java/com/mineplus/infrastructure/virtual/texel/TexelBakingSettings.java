package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.ModelMeta;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global texel surface baking settings (the {@code TEXEL_BAKING} section of
 * {@code settings.mp.yml}); per-model {@code .meta.json} overrides take precedence.
 *
 * <p>Defaults are chosen so a server that never touches the config sees zero behavior
 * change: {@code AUTO} only activates for FULL-strategy faces with a resolvable PNG,
 * and shipped models have no adjacent PNGs.</p>
 *
 * <p>Bake-time optimizations all default on and are strictly plate-count reducers:
 * {@code UNIFORM_AREA_DETECTION} coalesces large flat regions into one stretched
 * plate, {@code ADAPTIVE_BUDGETING} scales the per-face ceiling with face texel
 * area, {@code BUDGET_FALLBACK_TO_SIMPLE_COLOR} degrades an over-budget face to a
 * single dominant-color plate instead of dropping it to legacy rendering, and
 * {@code REUSE_SYMMETRIC_FACES} memoizes identical (texture + UV + orientation)
 * face sampling passes.</p>
 *
 * @param uniformAreaDetection       merge large uniform color regions into one rect
 * @param uniformAreaMinSize         minimum region area (in texels) to coalesce
 * @param uniformAreaOklabThreshold  perceptual tolerance when coalescing stretchable regions
 * @param adaptiveBudgeting          scale the per-face plate ceiling with face texel area
 * @param budgetFallbackToSimpleColor degrade an over-budget face to one dominant-color plate
 * @param reuseSymmetricFaces        memoize identical face sampling passes
 */
public record TexelBakingSettings(
        boolean enabled,
        ModelMeta.TexelMode mode,
        ModelMeta.TexelDetail detail,
        int maxPlatesPerFace,
        int maxPlatesPerInstance,
        int maxGridEdge,
        boolean uniformAreaDetection,
        int uniformAreaMinSize,
        float uniformAreaOklabThreshold,
        boolean adaptiveBudgeting,
        boolean budgetFallbackToSimpleColor,
        boolean reuseSymmetricFaces
) {

    /** Smallest per-face adaptive ceiling, so adaptive budgeting never starves detail. */
    private static final int ADAPTIVE_FACE_FLOOR = 16;
    /** Face texels per one unit of adaptive budget (area / this = candidate ceiling). */
    private static final int ADAPTIVE_TEXELS_PER_PLATE = 4;

    public TexelBakingSettings {
        maxPlatesPerFace = Math.max(1, maxPlatesPerFace);
        maxPlatesPerInstance = Math.max(1, maxPlatesPerInstance);
        maxGridEdge = Math.max(1, maxGridEdge);
        uniformAreaMinSize = Math.max(4, uniformAreaMinSize);
        uniformAreaOklabThreshold = Math.max(0.0f, uniformAreaOklabThreshold);
    }

    public static TexelBakingSettings defaults() {
        return new TexelBakingSettings(
                true,
                ModelMeta.TexelMode.AUTO,
                ModelMeta.TexelDetail.FACE,
                96,
                150,
                64,
                true,
                8,
                0.05f,
                true,
                true,
                true
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

    /**
     * Per-face plate ceiling for a face of {@code gridWidth x gridHeight} texels.
     * With adaptive budgeting on, the ceiling grows with face area so large faces
     * keep proportional detail while small faces stay cheap; it is always clamped
     * to {@code [ADAPTIVE_FACE_FLOOR, maxPlatesPerFace]} so it can only tighten,
     * never exceed, the configured hard ceiling.
     *
     * @param gridWidth  face texels along U (0 when unknown)
     * @param gridHeight face texels along V (0 when unknown)
     * @param hardCeiling the configured (or meta-overridden) per-face ceiling
     */
    public int effectiveFaceCeiling(int gridWidth, int gridHeight, int hardCeiling) {
        if (!adaptiveBudgeting || gridWidth <= 0 || gridHeight <= 0) {
            return hardCeiling;
        }
        int area = gridWidth * gridHeight;
        int candidate = Math.max(ADAPTIVE_FACE_FLOOR, area / ADAPTIVE_TEXELS_PER_PLATE);
        return Math.min(hardCeiling, candidate);
    }

    public static TexelBakingSettings parse(FileConfiguration yaml, TexelBakingSettings fallback) {
        if (!yaml.isConfigurationSection("TEXEL_BAKING")) {
            return fallback;
        }
        ConfigurationSection section = yaml.getConfigurationSection("TEXEL_BAKING");
        TexelBakingSettings defaults = defaults();
        return new TexelBakingSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                ModelMeta.TexelMode.fromKey(section.getString("MODE"), defaults.mode()),
                ModelMeta.TexelDetail.fromKey(section.getString("DETAIL"), defaults.detail()),
                section.getInt("MAX_PLATES_PER_FACE", defaults.maxPlatesPerFace()),
                section.getInt("MAX_PLATES_PER_INSTANCE", defaults.maxPlatesPerInstance()),
                section.getInt("MAX_GRID_EDGE", defaults.maxGridEdge()),
                readBoolean(section, "UNIFORM_AREA_DETECTION", "uniformAreaDetection", defaults.uniformAreaDetection()),
                readInt(section, "UNIFORM_AREA_MIN_SIZE", "uniformAreaMinSize", defaults.uniformAreaMinSize()),
                (float) readDouble(section, "UNIFORM_AREA_OKLAB_THRESHOLD", "uniformAreaOklabThreshold",
                        defaults.uniformAreaOklabThreshold()),
                readBoolean(section, "ADAPTIVE_BUDGETING", "adaptiveBudgeting", defaults.adaptiveBudgeting()),
                readBoolean(section, "BUDGET_FALLBACK_TO_SIMPLE_COLOR", "budgetFallbackToSimpleColor",
                        defaults.budgetFallbackToSimpleColor()),
                readBoolean(section, "REUSE_SYMMETRIC_FACES", "reuseSymmetricFaces", defaults.reuseSymmetricFaces())
        );
    }

    private static boolean readBoolean(ConfigurationSection section, String upperKey, String camelKey, boolean fallback) {
        return section.contains(upperKey) ? section.getBoolean(upperKey) : section.getBoolean(camelKey, fallback);
    }

    private static int readInt(ConfigurationSection section, String upperKey, String camelKey, int fallback) {
        return section.contains(upperKey) ? section.getInt(upperKey) : section.getInt(camelKey, fallback);
    }

    private static double readDouble(ConfigurationSection section, String upperKey, String camelKey, double fallback) {
        return section.contains(upperKey) ? section.getDouble(upperKey) : section.getDouble(camelKey, fallback);
    }
}
