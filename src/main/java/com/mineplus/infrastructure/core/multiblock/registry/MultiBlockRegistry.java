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

    public Collection<MultiBlockInstance> getInstances() {
        return Collections.unmodifiableCollection(instancesById.values());
    }

    public boolean hasAt(BlockCoordinate coordinate) {
        return instancesByLocation.containsKey(coordinate);
    }

    public synchronized void clearInstances() {
        instancesById.clear();
        instancesByLocation.clear();
        renderedModelIdToInstanceId.clear();
    }
}
