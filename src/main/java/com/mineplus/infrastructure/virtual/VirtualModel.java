package com.mineplus.infrastructure.virtual;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record VirtualModel(
        String name,
        List<BakedCube> cubes,
        Map<String, String> textureMappings,
        Resolution resolution,
        String modelFormat,
        List<VectorAnchor> anchors
) {

    public VirtualModel {
        cubes = List.copyOf(cubes);
        textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
        resolution = resolution == null ? new Resolution(16, 16) : resolution;
        modelFormat = modelFormat == null || modelFormat.isBlank() ? null : modelFormat;
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings) {
        this(name, cubes, textureMappings, null, null, null);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings,
                       Resolution resolution, String modelFormat) {
        this(name, cubes, textureMappings, resolution, modelFormat, null);
    }

    /**
     * Distinct texture names referenced by this model, in deterministic order.
     * Drives the per-model texture-resolution report.
     */
    public Set<String> textureNames() {
        if (textureMappings == null || textureMappings.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(textureMappings.values());
    }

    /** Texture resolution in pixels (bbmodel {@code resolution.width/height}), default 16x16. */
    public record Resolution(int width, int height) {
        public Resolution {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }
}
