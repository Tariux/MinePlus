package com.mineplus.infrastructure.virtual;

import java.util.List;
import java.util.Map;

public record VirtualModel(
        String name,
        List<BakedCube> cubes,
        Map<String, String> textureMappings,
        Resolution resolution,
        TextureMode textureMode,
        String defaultTextureName
) {

    public VirtualModel {
        cubes = List.copyOf(cubes);
        textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
        resolution = resolution == null ? new Resolution(16, 16) : resolution;
        textureMode = textureMode == null ? TextureMode.BOX : textureMode;
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings) {
        this(name, cubes, textureMappings, null, null, null);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings,
                        Resolution resolution, TextureMode textureMode) {
        this(name, cubes, textureMappings, resolution, textureMode, null);
    }

    /** Texture used in UV mode: explicit default, else the one referenced by the most faces. */
    public String uvTextureName() {
        if (defaultTextureName != null && !defaultTextureName.isBlank()) {
            return defaultTextureName;
        }
        Map<String, Integer> counts = new java.util.HashMap<>();
        String best = null;
        int bestCount = -1;
        for (BakedCube cube : cubes) {
            for (BakedFace face : cube.faces().values()) {
                String textureName = face.textureName();
                if (textureName == null || textureName.isBlank()) {
                    continue;
                }
                int count = counts.merge(textureName, 1, Integer::sum);
                if (count > bestCount) {
                    bestCount = count;
                    best = textureName;
                }
            }
        }
        return best;
    }

    /** Texture resolution in pixels (bbmodel {@code resolution.width/height}), default 16x16. */
    public record Resolution(int width, int height) {
        public Resolution {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }

    /** Per-model texture application mode. */
    public enum TextureMode {
        /** Per-face textures from {@link BakedFace#textureName()} (box mapping). */
        BOX,
        /** Single texture applied to the whole geometry (UV mapping). */
        UV;

        public static TextureMode fromKey(String key, TextureMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return TextureMode.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }
}
