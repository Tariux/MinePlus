package com.mineplus.infrastructure.virtual;

import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-model override file {@code models/<key>.meta.json}:
 * <pre>{@code
 * {
 *   "originMode": "GRID",
 *   "collisionMode": "SURFACE",
 *   "autoplay": ["rotate_gear"],
 *   "texelMode": "AUTO",
 *   "texelDetail": "FACE",
 *   "maxTexelPlatesPerFace": 96,
 *   "maxTexelPlatesPerInstance": 150,
 *   "texelBrightness": 15,
 *   "voxelMode": "AUTO",
 *   "maxVoxelDisplays": 1024
 * }
 * }</pre>
 * Any omitted field falls back to the global settings default.
 */
public record ModelMeta(
        OriginMode originMode,
        CollisionMode collisionMode,
        List<String> autoplay,
        TexelMode texelMode,
        TexelDetail texelDetail,
        Integer maxTexelPlatesPerFace,
        Integer maxTexelPlatesPerInstance,
        Integer texelBrightness,
        VoxelMode voxelMode,
        Integer maxVoxelDisplays
) {

    public ModelMeta {
        autoplay = autoplay == null ? List.of() : List.copyOf(autoplay);
        maxTexelPlatesPerFace = maxTexelPlatesPerFace == null ? null : Math.max(1, maxTexelPlatesPerFace);
        maxTexelPlatesPerInstance = maxTexelPlatesPerInstance == null
                ? null : Math.max(1, maxTexelPlatesPerInstance);
        texelBrightness = texelBrightness == null
                ? null : Math.max(0, Math.min(15, texelBrightness));
        maxVoxelDisplays = maxVoxelDisplays == null ? null : Math.max(1, maxVoxelDisplays);
    }

    /** Compatibility constructor predating the voxel rendering overrides. */
    public ModelMeta(
            OriginMode originMode,
            CollisionMode collisionMode,
            List<String> autoplay,
            TexelMode texelMode,
            TexelDetail texelDetail,
            Integer maxTexelPlatesPerFace,
            Integer maxTexelPlatesPerInstance,
            Integer texelBrightness
    ) {
        this(originMode, collisionMode, autoplay, texelMode, texelDetail,
                maxTexelPlatesPerFace, maxTexelPlatesPerInstance, texelBrightness, null, null);
    }

    public enum OriginMode {
        /**
         * Detect from the model's {@code meta.model_format} and geometry extent:
         * vanilla {@code java_block}/{@code java_item} spaces ([0..16] pixels, corner
         * origin) anchor at the block corner unless the geometry is center-authored;
         * every other format anchors pixel (0,0,0) at the block center.
         */
        AUTO,
        /**
         * Blockbench free-format convention: pixel (0,0,0) is the center of the anchor
         * block at its base; a full block spans pixels [-8..8] horizontally and [0..16]
         * vertically. Single-block centered models occupy exactly one block.
         */
        CENTER,
        /**
         * Vanilla java_block convention: pixel (0,0,0) is the north-west-bottom corner
         * of the anchor block; a full block spans pixels [0..16] on every axis.
         */
        GRID;

        /** @deprecated legacy alias for {@link #CENTER}. */
        @Deprecated
        public static final OriginMode LEGACY = CENTER;

        /** Resolves the anchor convention for a bbmodel {@code meta.model_format} value. */
        public static OriginMode forFormat(String modelFormat) {
            if (modelFormat == null || modelFormat.isBlank()) {
                return CENTER;
            }
            String format = modelFormat.trim().toLowerCase(Locale.ROOT);
            if (format.contains("java_block") || format.contains("java_item")
                    || format.equals("modded_block")) {
                return GRID;
            }
            return CENTER;
        }

        /**
         * Resolves the anchor convention from the model format <i>and</i> the actual
         * geometry extent. Authors frequently build centered models (pixel (0,0,0) =
         * block center, extent like [-8..8]) even in {@code java_block} projects; the
         * format label alone would corner-anchor those and swing them out of the block
         * on rotated placements. Heuristic: when the geometry's xz bounding-box center
         * sits within 2 pixels of the model origin, the model is center-authored.
         */
        public static OriginMode forModel(String modelFormat, java.util.List<BakedCube> cubes) {
            OriginMode byFormat = forFormat(modelFormat);
            if (byFormat != GRID) {
                return byFormat;
            }
            if (cubes == null || cubes.isEmpty()) {
                return GRID;
            }

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (BakedCube cube : cubes) {
                org.joml.Matrix4f m = new org.joml.Matrix4f()
                        .translate(cube.translation())
                        .rotate(cube.leftRotation())
                        .scale(cube.scale())
                        .rotate(cube.rightRotation());
                for (int corner = 0; corner < 8; corner++) {
                    org.joml.Vector3f p = new org.joml.Vector3f(
                            (corner & 1) == 0 ? 0.0f : 1.0f,
                            (corner & 2) == 0 ? 0.0f : 1.0f,
                            (corner & 4) == 0 ? 0.0f : 1.0f);
                    m.transformPosition(p);
                    minX = Math.min(minX, p.x * 16.0f);
                    maxX = Math.max(maxX, p.x * 16.0f);
                    minZ = Math.min(minZ, p.z * 16.0f);
                    maxZ = Math.max(maxZ, p.z * 16.0f);
                }
            }

            float centerX = (minX + maxX) / 2.0f;
            float centerZ = (minZ + maxZ) / 2.0f;
            boolean centeredNearOrigin = Math.abs(centerX) <= 2.0f && Math.abs(centerZ) <= 2.0f;
            return centeredNearOrigin ? CENTER : GRID;
        }

        public static OriginMode fromKey(String key, OriginMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            String normalized = key.trim().toUpperCase(Locale.ROOT);
            if ("LEGACY".equals(normalized)) {
                return CENTER;
            }
            try {
                return OriginMode.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public enum CollisionMode {
        /** Legacy full-model-AABB fill (compat escape hatch). */
        AABB,
        /** Per-cube volumetric voxelization: solid parts solid, voids open. */
        GEOMETRY,
        /** GEOMETRY plus interior hollowing: walk-in structures. */
        SURFACE;

        public static CollisionMode fromKey(String key, CollisionMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return CollisionMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    /**
     * Texel surface baking strategy for a model: decompose each face's UV-mapped
     * texture into per-pixel texels quantized to the vanilla flat-block palette
     * (see the {@code texel} package). {@code AUTO} only upgrades faces that would
     * otherwise render with the FULL strategy and have a resolvable PNG next to the
     * model; {@code ON} bakes every face with a resolvable PNG; {@code OFF} keeps the
     * legacy one-material-per-face pipeline.
     */
    public enum TexelMode {
        AUTO,
        ON,
        OFF;

        public static TexelMode fromKey(String key, TexelMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return TexelMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    /** Sampling policy per texel: one center sample, or area-averaged supersampling. */
    public enum TexelDetail {
        FACE,
        SUPERSAMPLE_2X2,
        SUPERSAMPLE_4X4;

        /** Sample grid edge length along each axis (1, 2 or 4). */
        public int sampleCount() {
            return switch (this) {
                case SUPERSAMPLE_2X2 -> 2;
                case SUPERSAMPLE_4X4 -> 4;
                default -> 1;
            };
        }

        public static TexelDetail fromKey(String key, TexelDetail fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return TexelDetail.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    /**
     * Voxel reconstruction strategy for a model: rebuild the model as 1x1x1
     * model-space voxels whose colors come from sampling the real geometry and
     * UV mapping (see the {@code voxel} package). {@code AUTO} only activates for
     * non-animated, axis-aligned, grid-snapped models with a resolvable PNG whose
     * voxel reconstruction stays inside the display budget; {@code ON} attempts it
     * for any non-animated model; {@code OFF} never voxelizes. Animated models
     * always keep the legacy pipeline — animations bind displays to cube bones.
     */
    public enum VoxelMode {
        AUTO,
        ON,
        OFF;

        public static VoxelMode fromKey(String key, VoxelMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return VoxelMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public static ModelMeta empty() {
        return new ModelMeta(null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return originMode == null && collisionMode == null && autoplay.isEmpty()
                && texelMode == null && texelDetail == null
                && maxTexelPlatesPerFace == null && maxTexelPlatesPerInstance == null
                && texelBrightness == null
                && voxelMode == null && maxVoxelDisplays == null;
    }

    public static ModelMeta load(File modelFile) {
        if (modelFile == null) {
            return empty();
        }
        String name = modelFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return empty();
        }
        File metaFile = new File(modelFile.getParentFile(), name.substring(0, dot) + ".meta.json");
        if (!metaFile.exists() || !metaFile.isFile()) {
            return empty();
        }

        try (com.google.gson.stream.JsonReader json =
                     new com.google.gson.stream.JsonReader(new java.io.BufferedReader(new java.io.FileReader(metaFile)))) {
            OriginMode originMode = null;
            CollisionMode collisionMode = null;
            List<String> autoplay = new ArrayList<>();
            TexelMode texelMode = null;
            TexelDetail texelDetail = null;
            Integer maxTexelPlatesPerFace = null;
            Integer maxTexelPlatesPerInstance = null;
            Integer texelBrightness = null;
            VoxelMode voxelMode = null;
            Integer maxVoxelDisplays = null;

            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "originMode" -> originMode = OriginMode.fromKey(readString(json), null);
                    case "collisionMode" -> collisionMode = CollisionMode.fromKey(readString(json), null);
                    case "autoplay" -> readStringArray(json, autoplay);
                    case "texelMode" -> texelMode = TexelMode.fromKey(readString(json), null);
                    case "texelDetail" -> texelDetail = TexelDetail.fromKey(readString(json), null);
                    case "maxTexelPlatesPerFace" -> maxTexelPlatesPerFace = readPositiveInt(json);
                    case "maxTexelPlatesPerInstance" -> maxTexelPlatesPerInstance = readPositiveInt(json);
                    case "texelBrightness" -> texelBrightness = readBrightness(json);
                    case "voxelMode" -> voxelMode = VoxelMode.fromKey(readString(json), null);
                    case "maxVoxelDisplays" -> maxVoxelDisplays = readPositiveInt(json);
                    default -> json.skipValue();
                }
            }
            json.endObject();

            return new ModelMeta(originMode, collisionMode, autoplay, texelMode, texelDetail,
                    maxTexelPlatesPerFace, maxTexelPlatesPerInstance, texelBrightness,
                    voxelMode, maxVoxelDisplays);
        } catch (Exception exception) {
            DebugLogger.warning("Failed to read model meta file '" + metaFile.getAbsolutePath() + "': "
                    + exception.getMessage());
            return empty();
        }
    }

    private static String readString(com.google.gson.stream.JsonReader json) throws Exception {
        if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        return json.nextString();
    }

    private static Integer readPositiveInt(com.google.gson.stream.JsonReader json) throws Exception {
        if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        try {
            int value = json.nextInt();
            return value > 0 ? value : null;
        } catch (NumberFormatException | IllegalStateException malformed) {
            json.skipValue();
            return null;
        }
    }

    /** Display brightness override (0-15); out-of-range or malformed values become null. */
    private static Integer readBrightness(com.google.gson.stream.JsonReader json) throws Exception {
        if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        try {
            int value = json.nextInt();
            return value >= 0 && value <= 15 ? value : null;
        } catch (NumberFormatException | IllegalStateException malformed) {
            json.skipValue();
            return null;
        }
    }

    private static void readStringArray(com.google.gson.stream.JsonReader json, List<String> output) throws Exception {
        if (json.peek() != com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return;
        }
        json.beginArray();
        while (json.hasNext()) {
            String value = readString(json);
            if (value != null && !value.isBlank()) {
                output.add(value.trim());
            }
        }
        json.endArray();
    }
}
