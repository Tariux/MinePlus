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
        String primaryTexture,
        int lightEmission,
        int boneIndex
) {

    public BakedCube(
            String name,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation,
            Map<CubeFace, BakedFace> faces,
            String primaryTexture
    ) {
        this(name, translation, leftRotation, scale, rightRotation, faces, primaryTexture, 0, -1);
    }

    public BakedCube(
            String name,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation,
            Map<CubeFace, BakedFace> faces,
            String primaryTexture,
            int lightEmission
    ) {
        this(name, translation, leftRotation, scale, rightRotation, faces, primaryTexture, lightEmission, -1);
    }

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
        lightEmission = Math.max(0, Math.min(15, lightEmission));
        boneIndex = Math.max(-1, boneIndex);
    }

    /** True when this cube carries no rotation, so its OBB equals its AABB (occupancy fast path). */
    public boolean isAxisAligned() {
        Quaternionf r = leftRotation;
        return (r.x * r.x + r.y * r.y + r.z * r.z) <= 1.0e-4f;
    }
}
