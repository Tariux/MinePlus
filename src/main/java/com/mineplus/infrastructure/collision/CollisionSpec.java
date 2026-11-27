package com.mineplus.infrastructure.collision;

import com.mineplus.infrastructure.collision.mask.RasterMode;
import com.mineplus.infrastructure.collision.source.AutoOccupancySource;
import com.mineplus.infrastructure.collision.source.BlueprintOccupancySource;
import com.mineplus.infrastructure.collision.source.DisplayTransform;
import com.mineplus.infrastructure.collision.source.FillOccupancySource;
import com.mineplus.infrastructure.collision.source.HybridOccupancySource;
import com.mineplus.infrastructure.collision.source.ModelResolver;
import com.mineplus.infrastructure.collision.source.OccupancySource;
import java.util.Locale;

/**
 * A declarative description of one model's collision volume.
 *
 * <p>Addons and the {@code collision} block of a model definition build a spec,
 * hand it to {@link CollisionService#fit}, and never touch the occupancy grid or
 * the fitter directly. A spec is immutable and free of Bukkit types, so it can
 * be built on the main thread and fitted on a worker.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * CollisionSpec chair = CollisionSpec.builder("fun:chair")
 *         .auto(modelJson)
 *         .profile("FURNITURE")
 *         .build();
 * FitResult fit = plugin.collisionService().fit(chair, ModelResolver.NONE);
 * }</pre>
 */
public final class CollisionSpec {

    /** Where the occupancy grid comes from. */
    public enum Source {
        /** Voxelised from model JSON. */
        AUTO,
        /** Hand-authored blueprint cells only. */
        BLUEPRINT,
        /** The whole bounding box, minus an inset. */
        FILL,
        /** AUTO with a blueprint overlay. */
        HYBRID;

        public static Source fromKey(String key, Source fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            for (Source value : values()) {
                if (value.name().equalsIgnoreCase(key.trim())) {
                    return value;
                }
            }
            return fallback;
        }
    }

    private final String modelKey;
    private final Source source;
    private final String modelJson;
    private final String blueprintJson;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final double insetBlocks;
    private final DisplayTransform display;
    private final int resolution;
    private final RasterMode rasterMode;
    private final boolean thinPlanesAsSlabs;
    private final String profile;
    private final double yaw;

    private CollisionSpec(Builder builder) {
        this.modelKey = builder.modelKey;
        this.source = builder.source;
        this.modelJson = builder.modelJson;
        this.blueprintJson = builder.blueprintJson;
        this.sizeX = builder.sizeX;
        this.sizeY = builder.sizeY;
        this.sizeZ = builder.sizeZ;
        this.insetBlocks = builder.insetBlocks;
        this.display = builder.display;
        this.resolution = builder.resolution;
        this.rasterMode = builder.rasterMode;
        this.thinPlanesAsSlabs = builder.thinPlanesAsSlabs;
        this.profile = builder.profile;
        this.yaw = builder.yaw;
    }

    public static Builder builder(String modelKey) {
        return new Builder(modelKey);
    }

    public String modelKey() {
        return modelKey;
    }

    public Source source() {
        return source;
    }

    /** Fit profile name, or null to use the server default. */
    public String profile() {
        return profile;
    }

    public double yaw() {
        return yaw;
    }

    public int resolution() {
        return resolution;
    }

    /** FILL box in blocks; {@code {1,1,1}} for the other sources. */
    public int[] sizeHint() {
        return new int[] {sizeX, sizeY, sizeZ};
    }

    /**
     * Builds the occupancy grid for this spec.
     *
     * <p>Resolution, raster mode and the block budget fall back to
     * {@code settings} unless the spec overrides them.</p>
     *
     * @throws IllegalArgumentException when the spec lacks the JSON its source needs
     */
    public OccupancySource.OccupancyResult build(ModelResolver resolver,
            CollisionSettings settings) {
        CollisionSettings effective = settings == null ? CollisionSettings.defaults() : settings;
        int res = resolution <= 0 ? effective.resolution() : resolution;
        RasterMode raster = rasterMode == null ? effective.rasterMode() : rasterMode;
        int maxBlocks = Math.max(1, effective.maxBlocks());
        switch (source) {
            case BLUEPRINT:
                return BlueprintOccupancySource.fromText(requireBlueprint(), modelKey).build();
            case FILL:
                return new FillOccupancySource(res, sizeX, sizeY, sizeZ, insetBlocks).build();
            case HYBRID:
                return new HybridOccupancySource(auto(res, raster, resolver, maxBlocks),
                        BlueprintOccupancySource.fromText(requireBlueprint(), modelKey)).build();
            case AUTO:
            default:
                return auto(res, raster, resolver, maxBlocks).build();
        }
    }

    private String requireBlueprint() {
        if (blueprintJson == null || blueprintJson.isBlank()) {
            throw new IllegalArgumentException(source.name() + " collision for '" + modelKey
                    + "' needs blueprint JSON");
        }
        return blueprintJson;
    }

    private OccupancySource auto(int res, RasterMode raster, ModelResolver resolver,
            int maxBlocks) {
        if (modelJson == null || modelJson.isBlank()) {
            throw new IllegalArgumentException("AUTO collision for '" + modelKey
                    + "' needs model JSON");
        }
        return new AutoOccupancySource(modelJson, modelKey, display, res, raster,
                thinPlanesAsSlabs, resolver, maxBlocks);
    }

    /** Short description used in command output and cache keys. */
    public String describe() {
        return modelKey + " source=" + source.name().toLowerCase(Locale.ROOT)
                + " profile=" + (profile == null ? "default" : profile)
                + " yaw=" + yaw + " res=" + resolution;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Mutable builder; every setter returns itself. */
    public static final class Builder {
        private final String modelKey;
        private Source source = Source.AUTO;
        private String modelJson;
        private String blueprintJson;
        private int sizeX = 1;
        private int sizeY = 1;
        private int sizeZ = 1;
        private double insetBlocks = 0;
        private DisplayTransform display = DisplayTransform.IDENTITY;
        private int resolution = 0;
        private RasterMode rasterMode;
        private boolean thinPlanesAsSlabs = true;
        private String profile;
        private double yaw = 0;

        private Builder(String modelKey) {
            if (modelKey == null || modelKey.isBlank()) {
                throw new IllegalArgumentException("collision spec needs a model key");
            }
            this.modelKey = modelKey.trim();
        }

        private Builder(CollisionSpec spec) {
            this.modelKey = spec.modelKey;
            this.source = spec.source;
            this.modelJson = spec.modelJson;
            this.blueprintJson = spec.blueprintJson;
            this.sizeX = spec.sizeX;
            this.sizeY = spec.sizeY;
            this.sizeZ = spec.sizeZ;
            this.insetBlocks = spec.insetBlocks;
            this.display = spec.display;
            this.resolution = spec.resolution;
            this.rasterMode = spec.rasterMode;
            this.thinPlanesAsSlabs = spec.thinPlanesAsSlabs;
            this.profile = spec.profile;
            this.yaw = spec.yaw;
        }

        public Builder source(Source value) {
            this.source = value == null ? Source.AUTO : value;
            return this;
        }

        /** AUTO from model JSON (text, not a file). */
        public Builder auto(String modelJson) {
            this.modelJson = modelJson;
            this.source = Source.AUTO;
            return this;
        }

        public Builder modelJson(String modelJson) {
            this.modelJson = modelJson;
            return this;
        }

        public Builder blueprint(String blueprintJson) {
            this.blueprintJson = blueprintJson;
            return this;
        }

        /** HYBRID: AUTO base plus a blueprint overlay. */
        public Builder hybrid(String modelJson, String blueprintJson) {
            this.modelJson = modelJson;
            this.blueprintJson = blueprintJson;
            this.source = Source.HYBRID;
            return this;
        }

        /** FILL: the whole box in block units. */
        public Builder fill(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = Math.max(1, sizeX);
            this.sizeY = Math.max(1, sizeY);
            this.sizeZ = Math.max(1, sizeZ);
            this.source = Source.FILL;
            return this;
        }

        public Builder inset(double blocks) {
            this.insetBlocks = Math.max(0, blocks);
            return this;
        }

        public Builder display(DisplayTransform transform) {
            this.display = transform == null ? DisplayTransform.IDENTITY : transform;
            return this;
        }

        public Builder resolution(int value) {
            this.resolution = Math.max(0, Math.min(64, value));
            return this;
        }

        public Builder rasterMode(RasterMode value) {
            this.rasterMode = value;
            return this;
        }

        public Builder thinPlanesAsSlabs(boolean value) {
            this.thinPlanesAsSlabs = value;
            return this;
        }

        public Builder profile(String value) {
            this.profile = value == null || value.isBlank() ? null : value.trim();
            return this;
        }

        public Builder yaw(double degrees) {
            this.yaw = degrees;
            return this;
        }

        public CollisionSpec build() {
            if (source == Source.AUTO && (modelJson == null || modelJson.isBlank())) {
                throw new IllegalArgumentException("AUTO collision for '" + modelKey
                        + "' needs model JSON");
            }
            if ((source == Source.BLUEPRINT || source == Source.HYBRID)
                    && (blueprintJson == null || blueprintJson.isBlank())) {
                throw new IllegalArgumentException(source.name() + " collision for '" + modelKey
                        + "' needs blueprint JSON");
            }
            return new CollisionSpec(this);
        }
    }
}
