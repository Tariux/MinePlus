package com.mineplus.infrastructure.virtual;

import java.util.HashSet;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public record VirtualBoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static VirtualBoundingBox calculate(VirtualModel model) {
        if (model.cubes().isEmpty()) {
            return new VirtualBoundingBox(0, 0, 0, 0, 0, 0);
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (BakedCube cube : model.cubes()) {
            Matrix4f matrix = new Matrix4f()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());

            for (Vector3f vertex : cubeVertices()) {
                Vector3f transformed = matrix.transformPosition(new Vector3f(vertex));
                minX = Math.min(minX, transformed.x);
                minY = Math.min(minY, transformed.y);
                minZ = Math.min(minZ, transformed.z);

                maxX = Math.max(maxX, transformed.x);
                maxY = Math.max(maxY, transformed.y);
                maxZ = Math.max(maxZ, transformed.z);
            }
        }

        int roundedMinX = Math.max(0, (int) Math.floor(minX));
        int roundedMinY = Math.max(0, (int) Math.floor(minY));
        int roundedMinZ = Math.max(0, (int) Math.floor(minZ));
        
        int roundedMaxX = Math.max(roundedMinX + 1, (int) Math.ceil(maxX));
        int roundedMaxY = Math.max(roundedMinY + 1, (int) Math.ceil(maxY));
        int roundedMaxZ = Math.max(roundedMinZ + 1, (int) Math.ceil(maxZ));

        return new VirtualBoundingBox(
                roundedMinX,
                roundedMinY,
                roundedMinZ,
                roundedMaxX,
                roundedMaxY,
                roundedMaxZ
        );
    }

    public Set<org.bukkit.util.Vector> getOccupiedOffsets() {
        Set<org.bukkit.util.Vector> offsets = new HashSet<>();

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    offsets.add(new org.bukkit.util.Vector(x, y, z));
                }
            }
        }
        if (offsets.isEmpty()) {
            offsets.add(new org.bukkit.util.Vector(0, 0, 0));
        }

        return offsets;
    }

    private static Vector3f[] cubeVertices() {
        return new Vector3f[]{
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 0f, 0f),
                new Vector3f(0f, 1f, 0f),
                new Vector3f(1f, 1f, 0f),
                new Vector3f(0f, 0f, 1f),
                new Vector3f(1f, 0f, 1f),
                new Vector3f(0f, 1f, 1f),
                new Vector3f(1f, 1f, 1f)
        };
    }
}
