package com.mineplus.infrastructure.virtual;

import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.Locale;

/**
 * Per-model override file {@code models/<key>.meta.json}:
 * <pre>{@code
 * {
 *   "textureMode": "UV",
 *   "originMode": "GRID",
 *   "collisionMode": "SURFACE"
 * }
 * }</pre>
 * Any omitted field falls back to the global settings default.
 */
public record ModelMeta(
        VirtualModel.TextureMode textureMode,
        OriginMode originMode,
        CollisionMode collisionMode
) {

    public enum OriginMode {
        /**
         * Vanilla/Blockbench convention: pixel (0,0,0) is the center of the anchor block at
         * its base; a full block spans pixels [-8..8] horizontally and [0..16] vertically.
         * Single-block models occupy exactly one block and rotate about the block center.
         */
        CENTER,
        /** Corner-anchored: pixel (0,0,0) is the north-west-bottom corner of the anchor block. */
        GRID;

        /** @deprecated legacy alias for {@link #CENTER} (the old +0.5 spawn offset was correct). */
        @Deprecated
        public static final OriginMode LEGACY = CENTER;

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

    public static ModelMeta empty() {
        return new ModelMeta(null, null, null);
    }

    public boolean isEmpty() {
        return textureMode == null && originMode == null && collisionMode == null;
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
            VirtualModel.TextureMode textureMode = null;
            OriginMode originMode = null;
            CollisionMode collisionMode = null;

            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                switch (field) {
                    case "textureMode" -> textureMode = VirtualModel.TextureMode.fromKey(readString(json), null);
                    case "originMode" -> originMode = OriginMode.fromKey(readString(json), null);
                    case "collisionMode" -> collisionMode = CollisionMode.fromKey(readString(json), null);
                    default -> json.skipValue();
                }
            }
            json.endObject();

            return new ModelMeta(textureMode, originMode, collisionMode);
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
}
