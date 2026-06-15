package com.mineplus.infrastructure.persistence.snapshot;

import java.util.Map;
import java.util.UUID;

public record VirtualBlockSnapshot(
        UUID id,
        String modelName,
        String world,
        int x,
        int y,
        int z,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        Map<String, UUID> cubeEntities
) {
}
