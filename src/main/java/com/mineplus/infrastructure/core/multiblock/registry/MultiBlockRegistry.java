package com.mineplus.infrastructure.core.multiblock.registry;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.util.StringNormalizer;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Type registry for MultiBlock definitions and live instance indexing by UUID and location.
 *
 * <p>Thread-safety: All mutation methods (addInstance, removeInstance, registerType, etc.)
 * are synchronized. All access is expected to occur on the main server thread.
 */
public final class MultiBlockRegistry {

    private final Map<String, MultiBlockType> types;
    private final Map<String, MultiBlockHook> hookOverrides;
    private final Map<UUID, MultiBlockInstance> instancesById;
    private final Map<BlockCoordinate, UUID> instancesByLocation;
    private final Map<UUID, UUID> renderedModelIdToInstanceId;

    public MultiBlockRegistry() {
        this.types = new LinkedHashMap<>();
        this.hookOverrides = new LinkedHashMap<>();
        this.instancesById = new LinkedHashMap<>();
        this.instancesByLocation = new LinkedHashMap<>();
        this.renderedModelIdToInstanceId = new LinkedHashMap<>();
    }

    public synchronized void registerType(MultiBlockType type) {
        String id = StringNormalizer.normalize(type.id());
        MultiBlockHook override = hookOverrides.get(id);
        if (override != null) {
            type = new MultiBlockType(
                    type.id(),
                    type.displayName(),
                    type.levels(),
                    override,
                    type.guiKey()
            );
        }
        types.put(id, type);
    }

    public void registerHookOverride(String typeId, MultiBlockHook hook) {
        String key = StringNormalizer.normalize(typeId);
        if (key.isEmpty() || hook == null) {
            return;
        }

        hookOverrides.put(key, hook);
        MultiBlockType existing = types.get(key);
        if (existing == null) {
            return;
        }

        types.put(key, new MultiBlockType(
                existing.id(),
                existing.displayName(),
                existing.levels(),
                hook,
                existing.guiKey()
        ));
    }

    public synchronized void clearTypes() {
        types.clear();
    }

    public MultiBlockType getType(String id) {
        return types.get(StringNormalizer.normalize(id));
    }

    public Collection<MultiBlockType> getTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    public Set<String> typeKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(types.keySet()));
    }

    public synchronized void addInstance(MultiBlockInstance instance) {
        instancesById.put(instance.id(), instance);
        instancesByLocation.put(instance.coordinate(), instance.id());
        if (instance.renderedModelId() != null) {
            renderedModelIdToInstanceId.put(instance.renderedModelId(), instance.id());
        }
    }

    public synchronized void bindRenderedModelId(UUID instanceId, UUID renderedModelId) {
        MultiBlockInstance instance = instancesById.get(instanceId);
        if (instance == null) {
            return;
        }
        UUID oldId = instance.renderedModelId();
        if (oldId != null) {
            renderedModelIdToInstanceId.remove(oldId);
        }
        instance.setRenderedModelId(renderedModelId);
        if (renderedModelId != null) {
            renderedModelIdToInstanceId.put(renderedModelId, instanceId);
        }
    }

    public synchronized MultiBlockInstance removeInstance(UUID id) {
        MultiBlockInstance removed = instancesById.remove(id);
        if (removed != null) {
            instancesByLocation.remove(removed.coordinate());
            if (removed.renderedModelId() != null) {
                renderedModelIdToInstanceId.remove(removed.renderedModelId());
            }
        }
        return removed;
    }

    public MultiBlockInstance getInstance(UUID id) {
        return instancesById.get(id);
    }

    public MultiBlockInstance getByLocation(BlockCoordinate coordinate) {
        UUID id = instancesByLocation.get(coordinate);
        return id == null ? null : instancesById.get(id);
    }

    public MultiBlockInstance getInstanceByRenderedModelId(UUID renderedModelId) {
        UUID instanceId = renderedModelIdToInstanceId.get(renderedModelId);
        return instanceId == null ? null : instancesById.get(instanceId);
    }

    /**
     * Returns a snapshot copy of all live instances. A copy (rather than a
     * live view) guarantees that callers iterating while a hook removes an
     * instance mid-loop — the tick loop being the canonical case — cannot hit
     * a {@code ConcurrentModificationException} or see partially-mutated state.
     */
    public synchronized List<MultiBlockInstance> getInstances() {
        return List.copyOf(instancesById.values());
    }

    public boolean hasAt(BlockCoordinate coordinate) {
        return instancesByLocation.containsKey(coordinate);
    }

    /**
     * Finds all instances within a cube of the given half-extent around a
     * coordinate (Chebyshev distance {@code <= radius}). Enables spatial
     * gameplay scenarios such as auto-connecting pipe networks: a placed pump
     * can discover adjacent filters without knowing their UUIDs.
     *
     * <p>Implementation is O((2r+1)^3) map lookups over the location index —
     * cheap for the small radii such scenarios use (typically 1–3). The
     * anchor coordinate itself is excluded unless an instance sits exactly
     * on it, in which case it is included.
     *
     * @param coordinate the anchor coordinate
     * @param radius     the Chebyshev radius (0 = the anchor block only, 1 = 3x3x3)
     * @return an unmodifiable set of nearby instances, excluding the anchor instance
     *         if one exists exactly at the anchor coordinate
     */
    public synchronized Set<MultiBlockInstance> getNearby(BlockCoordinate coordinate, int radius) {
        if (coordinate == null || radius < 0) {
            return Set.of();
        }
        Set<MultiBlockInstance> nearby = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockCoordinate probe = new BlockCoordinate(
                            coordinate.worldName(),
                            coordinate.x() + dx,
                            coordinate.y() + dy,
                            coordinate.z() + dz);
                    UUID id = instancesByLocation.get(probe);
                    if (id != null) {
                        MultiBlockInstance instance = instancesById.get(id);
                        if (instance != null) {
                            nearby.add(instance);
                        }
                    }
                }
            }
        }
        MultiBlockInstance anchor = getByLocation(coordinate);
        if (anchor != null) {
            nearby.remove(anchor);
        }
        return Collections.unmodifiableSet(nearby);
    }

    public synchronized void clearInstances() {
        instancesById.clear();
        instancesByLocation.clear();
        renderedModelIdToInstanceId.clear();
    }
}
