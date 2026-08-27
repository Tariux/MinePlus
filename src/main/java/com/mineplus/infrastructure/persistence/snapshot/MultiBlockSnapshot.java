package com.mineplus.infrastructure.persistence.snapshot;

import com.mineplus.infrastructure.core.multiblock.EntityStatus;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.joml.Quaternionf;

public record MultiBlockSnapshot(
        UUID id,
        String typeId,
        String world,
        int x,
        int y,
        int z,
        UUID owner,
        UUID creator,
        long createdAt,
        long placedAt,
        int level,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        String status,
        long lastHeartbeat,
        long lastValidatedAt,
        String modelKey,
        Map<String, String> metadata,
        Map<String, String> stateData,
        Set<UUID> linkedBlocks
) {

    public MultiBlockSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        stateData = stateData == null ? Map.of() : Map.copyOf(stateData);
        linkedBlocks = linkedBlocks == null ? Set.of() : Set.copyOf(linkedBlocks);
    }

    public static MultiBlockSnapshot from(MultiBlockInstance instance) {
        Quaternionf rotation = instance.rotation();
        BlockCoordinate coordinate = instance.coordinate();
        return new MultiBlockSnapshot(
                instance.id(),
                instance.typeId(),
                coordinate.worldName(),
                coordinate.x(),
                coordinate.y(),
                coordinate.z(),
                instance.owner(),
                instance.creator(),
                instance.createdAt(),
                instance.placedAt(),
                instance.level(),
                rotation.x,
                rotation.y,
                rotation.z,
                rotation.w,
                instance.status() == null ? null : instance.status().name(),
                instance.lastHeartbeat(),
                instance.lastValidatedAt(),
                instance.modelKey(),
                instance.metadata(),
                instance.stateData(),
                instance.linkedBlocks()
        );
    }

    public MultiBlockInstance toInstance() {
        EntityStatus resolvedStatus = status == null || status.isBlank()
                ? EntityStatus.CREATED
                : parseStatus(status);
        return new MultiBlockInstance(
                id,
                typeId,
                new BlockCoordinate(world, x, y, z),
                owner,
                creator,
                createdAt,
                placedAt,
                level,
                new Quaternionf(rotationX, rotationY, rotationZ, rotationW),
                null,
                resolvedStatus,
                lastHeartbeat,
                lastValidatedAt,
                modelKey,
                new LinkedHashMap<>(metadata),
                new LinkedHashMap<>(stateData),
                new LinkedHashSet<>(linkedBlocks)
        );
    }

    private static EntityStatus parseStatus(String value) {
        try {
            return EntityStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return EntityStatus.CREATED;
        }
    }
}
