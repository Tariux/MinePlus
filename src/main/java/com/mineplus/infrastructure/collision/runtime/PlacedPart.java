package com.mineplus.infrastructure.collision.runtime;

import com.mineplus.infrastructure.collision.fit.BackendType;
import java.util.UUID;

/**
 * One placed piece of a model's collision: a real block, a ghost block sent to
 * viewers, or an entity.
 *
 * <p>Mutable on purpose: the backend that applies the part records the entity
 * UUID it spawned and the block state it replaced, both needed to remove the
 * part cleanly. Instances are only touched on the world's owning thread.
 */
public final class PlacedPart {

    private final BackendType backend;
    private final int cellX;
    private final int cellY;
    private final int cellZ;
    private String state;
    private String originalState;
    private String entityType;
    private UUID entityId;

    public PlacedPart(BackendType backend, int cellX, int cellY, int cellZ, String state) {
        if (backend == null) {
            throw new IllegalArgumentException("part needs a backend");
        }
        this.backend = backend;
        this.cellX = cellX;
        this.cellY = cellY;
        this.cellZ = cellZ;
        this.state = state;
    }

    public BackendType backend() {
        return backend;
    }

    public int cellX() {
        return cellX;
    }

    public int cellY() {
        return cellY;
    }

    public int cellZ() {
        return cellZ;
    }

    /** Full block state for block-like backends, otherwise {@code null}. */
    public String state() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /** Block state that was in the world before this part was applied. */
    public String originalState() {
        return originalState;
    }

    public void setOriginalState(String originalState) {
        this.originalState = originalState;
    }

    /** Bukkit entity type name for entity backends, otherwise {@code null}. */
    public String entityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public UUID entityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public boolean isEntity() {
        return entityId != null;
    }
}
