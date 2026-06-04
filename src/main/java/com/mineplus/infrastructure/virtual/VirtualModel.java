package com.mineplus.infrastructure.virtual;

import java.util.Map;
import java.util.List;

public record VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings) {
    public VirtualModel {
        cubes = List.copyOf(cubes);
        textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
    }
}
