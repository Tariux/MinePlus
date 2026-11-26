package com.mineplus.infrastructure.virtual;

import com.mineplus.util.DebugLogger;
import org.bukkit.Material;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-model override file {@code models/<key>.meta.json}:
 * <pre>{@code
 * {
 *   "originMode": "GRID",
 *   "anchorMode": "LATTICE",
 *   "collisionMode": "SURFACE",
 *   "collisionMaterial": "SLIME",
 *   "light": "normal",
 *   "autoplay": ["rotate_gear"],
 *   "texelMode": "AUTO",
 *   "texelDetail": "FACE",
 *   "maxTexelPlatesPerFace": 96,
 *   "maxTexelPlatesPerInstance": 150,
 *   "texelBrightness": 15,
 *   "animationFile": "alchemy-animations.bbmodel",
 *   "displayFile": "alchemy-display.bbmodel",
 *   "triggers": { "reload": "reload_clip", "fire": "fire_clip" },
 *   "fillBox": [0, 0, 0, 0, 0, 0]
 * }
 * }</pre>
 * {@code fillBox} (optional) declares the model's own filling space: inclusive
 * min/max cell coordinates {@code [minX,minY,minZ,maxX,maxY,maxZ]} relative to
 * the anchor block. Only cells inside it may receive invisible collision
 * blocks; geometry elsewhere (a water wheel's ring over a river, for example)
 * never becomes solid, so water and other world content beside the model keeps
 * flowing. Omitted or malformed means fill the whole geometry-occupied volume.
 * Any omitted field falls back to the global settings default.
 */
public record ModelMeta(
        OriginMode originMode,
        AnchorMode anchorMode,
        CollisionMode collisionMode,
        CollisionMaterial collisionMaterial,
        LightTier light,
        List<String> autoplay,
        TexelMode texelMode,
        TexelDetail texelDetail,
        Integer maxTexelPlatesPerFace,
        Integer maxTexelPlatesPerInstance,
        Integer texelBrightness,
        ModelDisplay display,
        String itemParent,
        String animationFile,
        String displayFile,
        Map<String, String> triggers,
        int[] fillBox
) {

    public ModelMeta {
        autoplay = autoplay == null ? List.of() : List.copyOf(autoplay);
        maxTexelPlatesPerFace = maxTexelPlatesPerFace == null ? null : Math.max(1, maxTexelPlatesPerFace);
        maxTexelPlatesPerInstance = maxTexelPlatesPerInstance == null
                ? null : Math.max(1, maxTexelPlatesPerInstance);
        texelBrightness = texelBrightness == null
                ? null : Math.max(0, Math.min(15, texelBrightness));
        display = display == null ? ModelDisplay.EMPTY : display;
        itemParent = itemParent == null || itemParent.isBlank() ? null : itemParent.trim();
        animationFile = animationFile == null || animationFile.isBlank() ? null : animationFile.trim();
        displayFile = displayFile == null || displayFile.isBlank() ? null : displayFile.trim();
        triggers = triggers == null || triggers.isEmpty() ? Map.of() : Map.copyOf(triggers);
        anchorMode = anchorMode == null ? null : anchorMode;
        fillBox = normalizeFillBox(fillBox);
    }

    /**
     * Normalizes a declared filling space to {@code [minX,minY,minZ,maxX,maxY,maxZ]}
     * (min <= max per axis, defensive copy). Anything other than exactly six
     * entries becomes {@code null} (fill the whole geometry-occupied volume).
     */
    private static int[] normalizeFillBox(int[] box) {
        if (box == null || box.length != 6) {
            return null;
        }
        return new int[] {
                Math.min(box[0], box[3]), Math.min(box[1], box[4]), Math.min(box[2], box[5]),
                Math.max(box[0], box[3]), Math.max(box[1], box[4]), Math.max(box[2], box[5])
        };
    }

    /** True when the anchor-relative cell sits inside the declared filling space. */
    public static boolean fillBoxContains(int[] fillBox, int x, int y, int z) {
        return fillBox != null
                && x >= fillBox[0] && x <= fillBox[3]
                && y >= fillBox[1] && y <= fillBox[4]
                && z >= fillBox[2] && z <= fillBox[5];
    }

    /** This model's declared filling space, or {@code null} for the full geometry volume. */
    public int[] fillBox() {
        return fillBox;
    }

    /** Restricts occupied cells ({@code x,y,z} triples) to the declared filling space. */
    public int[] restrictCells(int[] cells) {
        if (fillBox == null || cells == null || cells.length == 0) {
            return cells;
        }
        int kept = 0;
        for (int i = 0; i + 2 < cells.length; i += 3) {
            if (fillBoxContains(fillBox, cells[i], cells[i + 1], cells[i + 2])) {
                kept++;
            }
        }
        int[] restricted = new int[kept * 3];
        int o = 0;
        for (int i = 0; i + 2 < cells.length; i += 3) {
            if (fillBoxContains(fillBox, cells[i], cells[i + 1], cells[i + 2])) {
                restricted[o++] = cells[i];
                restricted[o++] = cells[i + 1];
                restricted[o++] = cells[i + 2];
            }
        }
        return restricted;
    }

    /** Every anchor-relative cell of the declared filling space, as {@code x,y,z} triples. */
    public static int[] fillBoxCells(int[] fillBox) {
        if (fillBox == null) {
            return new int[0];
        }
        int size = (fillBox[3] - fillBox[0] + 1) * (fillBox[4] - fillBox[1] + 1) * (fillBox[5] - fillBox[2] + 1);
        int[] cells = new int[Math.max(0, size) * 3];
        int o = 0;
        for (int x = fillBox[0]; x <= fillBox[3]; x++) {
            for (int y = fillBox[1]; y <= fillBox[4]; y++) {
                for (int z = fillBox[2]; z <= fillBox[5]; z++) {
                    cells[o++] = x;
                    cells[o++] = y;
                    cells[o++] = z;
                }
            }
        }
        return cells;
    }

    /** Resolved anchor strategy: explicit meta value, else {@link AnchorMode#LATTICE}. */
    public AnchorMode resolvedAnchorMode() {
        return anchorMode == null ? AnchorMode.LATTICE : anchorMode;
    }

    /**
     * Vanilla block the collision lattice is built from. Every value keeps the
     * same occupied-cell computation — only the placed block constant changes, so
     * vanilla physics (bounce, climb, drag, slow, bubble) applies on custom
     * geometry with zero physics code of ours. {@code BARRIER} is the default.
     */
    public enum CollisionMaterial {
        BARRIER(Material.BARRIER),
        SLIME(Material.SLIME_BLOCK),
        HONEY(Material.HONEY_BLOCK),
        COBWEB(Material.COBWEB),
        POWDER_SNOW(Material.POWDER_SNOW),
        SOUL_SAND(Material.SOUL_SAND),
        BUBBLE_COLUMN(Material.BUBBLE_COLUMN);

        public final Material block;

        CollisionMaterial(Material block) {
            this.block = block;
        }

        public static CollisionMaterial fromKey(String key, CollisionMaterial fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return CollisionMaterial.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    /** The clip a trigger event maps to (case-insensitive), or {@code null}. */
    public String triggerClip(String event) {
        if (event == null || triggers.isEmpty()) {
            return null;
        }
        return triggers.get(event.trim().toLowerCase(Locale.ROOT));
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
        /** Compatibility alias: resolved through the same per-cube geometry path as {@link #GEOMETRY}. */
        AABB,
        /** Per-cube geometry-aware collision: solid parts solid, voids open. */
        GEOMETRY,
        /** GEOMETRY plus interior hollowing: walk-in structures. */
        SURFACE,
        /** Exactly one solid block at the placement anchor — the axle cell. */
        SINGLE;

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

    public static ModelMeta empty() {
        return new ModelMeta(null, null, null, null, LightTier.OFF, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return originMode == null && collisionMode == null && collisionMaterial == null
                && light == LightTier.OFF && autoplay.isEmpty()
                && texelMode == null && texelDetail == null
                && maxTexelPlatesPerFace == null && maxTexelPlatesPerInstance == null
                && texelBrightness == null && display.isEmpty() && itemParent == null
                && fillBox == null;
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
        // Two naming conventions ship in the wild: "wheel.meta.json" beside the
        // model and the BBModel-style "wheel.bbmodel.meta.json" the examples
        // install. Both must resolve, or the meta (collision space, light,
        // origin) silently never applies and the model fills its whole volume.
        File metaFile = new File(modelFile.getParentFile(), name.substring(0, dot) + ".meta.json");
        if (!metaFile.isFile()) {
            File fullNameMeta = new File(modelFile.getParentFile(), name + ".meta.json");
            if (!fullNameMeta.isFile()) {
                return empty();
            }
            metaFile = fullNameMeta;
        }

        try (com.google.gson.stream.JsonReader json =
                     new com.google.gson.stream.JsonReader(new java.io.BufferedReader(new java.io.FileReader(metaFile)))) {
            OriginMode originMode = null;
            AnchorMode anchorMode = null;
            CollisionMode collisionMode = null;
            CollisionMaterial collisionMaterial = null;
            LightTier light = LightTier.OFF;
            List<String> autoplay = new ArrayList<>();
            TexelMode texelMode = null;
            TexelDetail texelDetail = null;
            Integer maxTexelPlatesPerFace = null;
            Integer maxTexelPlatesPerInstance = null;
            Integer texelBrightness = null;
            ModelDisplay display = ModelDisplay.EMPTY;
            String itemParent = null;
            String animationFile = null;
            String displayFile = null;
            Map<String, String> triggers = new LinkedHashMap<>();
            int[] fillBox = null;

            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "originMode" -> originMode = OriginMode.fromKey(readString(json), null);
                    case "anchorMode" -> anchorMode = AnchorMode.fromKey(readString(json), null);
                    case "collisionMode" -> collisionMode = CollisionMode.fromKey(readString(json), null);
                    case "collisionMaterial" -> collisionMaterial = CollisionMaterial.fromKey(readString(json), null);
                    case "light" -> light = LightTier.fromKey(readString(json));
                    case "autoplay" -> readStringArray(json, autoplay);
                    case "texelMode" -> texelMode = TexelMode.fromKey(readString(json), null);
                    case "texelDetail" -> texelDetail = TexelDetail.fromKey(readString(json), null);
                    case "maxTexelPlatesPerFace" -> maxTexelPlatesPerFace = readPositiveInt(json);
                    case "maxTexelPlatesPerInstance" -> maxTexelPlatesPerInstance = readPositiveInt(json);
                    case "texelBrightness" -> texelBrightness = readBrightness(json);
                    case "display" -> display = ModelDisplay.fromJson(json);
                    case "itemParent" -> itemParent = readString(json);
                    case "animationFile" -> animationFile = readString(json);
                    case "displayFile" -> displayFile = readString(json);
                    case "fillBox" -> fillBox = readFillBox(json);
                    case "triggers" -> readTriggerMap(json, triggers);
                    default -> json.skipValue();
                }
            }
            json.endObject();

            return new ModelMeta(originMode, anchorMode, collisionMode, collisionMaterial, light, autoplay, texelMode, texelDetail,
                    maxTexelPlatesPerFace, maxTexelPlatesPerInstance, texelBrightness, display, itemParent,
                    animationFile, displayFile, triggers, fillBox);
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

    /** Reads an optional 6-int filling space: inclusive min/max cells relative to the anchor. */
    private static int[] readFillBox(com.google.gson.stream.JsonReader json) throws Exception {
        if (json.peek() != com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return null;
        }
        int[] box = new int[6];
        json.beginArray();
        int read = 0;
        while (json.hasNext() && read < 6) {
            if (json.peek() != com.google.gson.stream.JsonToken.NUMBER) {
                json.skipValue();
                return null;
            }
            box[read++] = json.nextInt();
        }
        while (json.hasNext()) {
            json.skipValue();
        }
        json.endArray();
        return read == 6 ? box : null;
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

    /** The declarative trigger map: event name (kept lowercase) -> clip name. */
    private static void readTriggerMap(com.google.gson.stream.JsonReader json, Map<String, String> output) throws Exception {
        if (json.peek() != com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return;
        }
        json.beginObject();
        while (json.hasNext()) {
            String event = json.nextName();
            String clip = readString(json);
            if (event != null && !event.isBlank() && clip != null && !clip.isBlank()) {
                output.put(event.trim().toLowerCase(Locale.ROOT), clip.trim());
            }
        }
        json.endObject();
    }
}
