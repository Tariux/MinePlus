package com.mineplus.infrastructure.core.multiblock;

import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.joml.Quaternionf;

public final class MultiBlockInstance {

    private final UUID id;
    private final String typeId;
    private final BlockCoordinate coordinate;
    private final UUID owner;
    private final UUID creator;
    private final long createdAt;
    private long placedAt;
    private int level;
    private Quaternionf rotation;
    private UUID renderedModelId;
    private EntityStatus status;
    private long lastHeartbeat;
    private long lastValidatedAt;
    private String modelKey;
    private final Map<String, String> metadata;
    private final Map<String, String> stateData;
    private final Set<UUID> linkedBlocks;

    public MultiBlockInstance(
            UUID id,
            String typeId,
            BlockCoordinate coordinate,
            UUID owner,
            UUID creator,
            long createdAt,
            long placedAt,
            int level,
            Quaternionf rotation,
            UUID renderedModelId,
            EntityStatus status,
            long lastHeartbeat,
            long lastValidatedAt,
            String modelKey,
            Map<String, String> metadata,
            Map<String, String> stateData,
            Set<UUID> linkedBlocks
    ) {
        this.id = id;
        this.typeId = typeId;
        this.coordinate = coordinate;
        this.owner = owner;
        this.creator = creator;
        this.createdAt = createdAt;
        this.placedAt = placedAt;
        this.level = level;
        this.rotation = new Quaternionf(rotation);
        this.renderedModelId = renderedModelId;
        this.status = status;
        this.lastHeartbeat = lastHeartbeat;
        this.lastValidatedAt = lastValidatedAt;
        this.modelKey = modelKey;
        this.metadata = new LinkedHashMap<>(metadata);
        this.stateData = new LinkedHashMap<>(stateData);
        this.linkedBlocks = new LinkedHashSet<>(linkedBlocks);
    }

    public UUID id() {
        return id;
    }

    public String typeId() {
        return typeId;
    }

    public BlockCoordinate coordinate() {
        return coordinate;
    }

    public UUID owner() {
        return owner;
    }

    public UUID creator() {
        return creator;
    }

    public long createdAt() {
        return createdAt;
    }

    public long placedAt() {
        return placedAt;
    }

    public void setPlacedAt(long placedAt) {
        this.placedAt = placedAt;
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public Quaternionf rotation() {
        return new Quaternionf(rotation);
    }

    public void setRotation(Quaternionf rotation) {
        this.rotation = new Quaternionf(rotation);
    }

    public UUID renderedModelId() {
        return renderedModelId;
    }

    public void setRenderedModelId(UUID renderedModelId) {
        this.renderedModelId = renderedModelId;
    }

    public EntityStatus status() {
        return status;
    }

    public void setStatus(EntityStatus status) {
        this.status = status;
    }

    public long lastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public long lastValidatedAt() {
        return lastValidatedAt;
    }

    public void setLastValidatedAt(long lastValidatedAt) {
        this.lastValidatedAt = lastValidatedAt;
    }

    public String modelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public Map<String, String> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public Map<String, String> mutableMetadata() {
        return metadata;
    }

    public Map<String, String> stateData() {
        return Collections.unmodifiableMap(stateData);
    }

    public Map<String, String> mutableStateData() {
        return stateData;
    }

    public Set<UUID> linkedBlocks() {
        return Collections.unmodifiableSet(linkedBlocks);
    }

    public Set<UUID> mutableLinkedBlocks() {
        return linkedBlocks;
    }
}
