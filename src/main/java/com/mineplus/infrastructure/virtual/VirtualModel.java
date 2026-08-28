package com.mineplus.infrastructure.virtual;

import java.util.List;
import java.util.Map;

public record VirtualModel(
        String name,
        List<BakedCube> cubes,
        Map<String, String> textureMappings,
        Resolution resolution,
        String modelFormat
) {

    public VirtualModel {
        cubes = List.copyOf(cubes);
        textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
        resolution = resolution == null ? new Resolution(16, 16) : resolution;
        modelFormat = modelFormat == null || modelFormat.isBlank() ? null : modelFormat;
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings) {
        this(name, cubes, textureMappings, null, null);
    }

    /** Texture resolution in pixels (bbmodel {@code resolution.width/height}), default 16x16. */
    public record Resolution(int width, int height) {
        public Resolution {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }
}
