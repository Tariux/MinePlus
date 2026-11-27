package com.mineplus.infrastructure.collision.runtime;

import com.mineplus.infrastructure.collision.fit.BackendType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One live model instance and the parts that give it collision.
 *
 * <p>The model origin is the north-west bottom corner of the fitted grid; a
 * part's world position is {@code origin + (cellX, cellY, cellZ)}.
 */
public final class PlacedFurniture {

    private final UUID id;
    private final String modelKey;
    private final String world;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final double yaw;
    private final String atlasKey;
    private final String profileName;
    private final int resolution;
    private final List<PlacedPart> parts = new ArrayList<>();

    public PlacedFurniture(UUID id, String modelKey, String world, double originX, double originY,
            double originZ, double yaw, String atlasKey, String profileName, int resolution) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.modelKey = modelKey;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.yaw = yaw;
        this.atlasKey = atlasKey;
        this.profileName = profileName;
        this.resolution = resolution;
    }

    public UUID id() {
        return id;
    }

    public String modelKey() {
        return modelKey;
    }

    public String world() {
        return world;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    public double yaw() {
        return yaw;
    }

    public String atlasKey() {
        return atlasKey;
    }

    public String profileName() {
        return profileName;
    }

    public int resolution() {
        return resolution;
    }

    public List<PlacedPart> parts() {
        return parts;
    }

    public void add(PlacedPart part) {
        parts.add(part);
    }

    public int count(BackendType backend) {
        int total = 0;
        for (PlacedPart part : parts) {
            if (part.backend() == backend) {
                total++;
            }
        }
        return total;
    }
}
