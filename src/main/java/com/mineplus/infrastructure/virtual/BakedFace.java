package com.mineplus.infrastructure.virtual;

public record BakedFace(
        float u1,
        float v1,
        float u2,
        float v2,
        int rotation,
        String textureReference,
        String textureName
) {
}
