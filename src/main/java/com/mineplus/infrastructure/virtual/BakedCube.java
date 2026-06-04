package com.mineplus.infrastructure.virtual;

import java.util.EnumMap;
import java.util.Map;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public record BakedCube(
        String name,
        Vector3f translation,
        Quaternionf leftRotation,
        Vector3f scale,
        Quaternionf rightRotation,
        Map<CubeFace, BakedFace> faces,
        String primaryTexture
) {
    public BakedCube {
        name = name == null ? "cube" : name;
        translation = new Vector3f(translation);
        leftRotation = new Quaternionf(leftRotation);
        scale = new Vector3f(scale);
        rightRotation = new Quaternionf(rightRotation);
        EnumMap<CubeFace, BakedFace> faceCopy = new EnumMap<>(CubeFace.class);
        if (faces != null) {
            faceCopy.putAll(faces);
        }
        faces = Map.copyOf(faceCopy);
    }
}
