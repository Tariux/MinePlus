package com.mineplus.infrastructure.core.multiblock.registry;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MultiBlockRegistry {

    private final Map<String, MultiBlockType> types;
    private final Map<String, MultiBlockHook> hookOverrides;
    private final Map<UUID, MultiBlockInstance> instancesById;
    private final Map<BlockCoordinate, UUID> instancesByLocation;

    public MultiBlockRegistry() {
        this.types = new LinkedHashMap<>();
        this.hookOverrides = new LinkedHashMap<>();
        this.instancesById = new LinkedHashMap<>();
        this.instancesByLocation = new LinkedHashMap<>();
    }

    public void registerType(MultiBlockType type) {
        String id = normalize(type.id());
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
        String key = normalize(typeId);
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

    public void clearTypes() {
        types.clear();
    }

    public MultiBlockType getType(String id) {
        return types.get(normalize(id));
    }

    public Collection<MultiBlockType> getTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    public Set<String> typeKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(types.keySet()));
    }

    public void addInstance(MultiBlockInstance instance) {
        instancesById.put(instance.id(), instance);
        instancesByLocation.put(instance.coordinate(), instance.id());
    }

    public MultiBlockInstance removeInstance(UUID id) {
        MultiBlockInstance removed = instancesById.remove(id);
        if (removed != null) {
            instancesByLocation.remove(removed.coordinate());
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

    public Collection<MultiBlockInstance> getInstances() {
        return Collections.unmodifiableCollection(instancesById.values());
    }

    public boolean hasAt(BlockCoordinate coordinate) {
        return instancesByLocation.containsKey(coordinate);
    }

    public void clearInstances() {
        instancesById.clear();
        instancesByLocation.clear();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
