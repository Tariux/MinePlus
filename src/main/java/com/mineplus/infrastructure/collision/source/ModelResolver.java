package com.mineplus.infrastructure.collision.source;

import java.util.Map;

/** Resolves a model's {@code parent} reference to raw model JSON. */
@FunctionalInterface
public interface ModelResolver {

    /** No parents available; parent chains are truncated with a warning. */
    ModelResolver NONE = name -> null;

    /**
     * Returns the JSON text for a parent model name, or null when unknown.
     * Must not touch Bukkit state: AUTO voxelisation runs off-thread.
     */
    String resolve(String parentName);

    /** A resolver backed by an in-memory map (tests, baked packs). */
    static ModelResolver of(Map<String, String> byName) {
        return name -> byName == null ? null : byName.get(name);
    }
}
