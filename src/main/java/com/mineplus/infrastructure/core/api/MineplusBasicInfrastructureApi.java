package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;

public final class MineplusBasicInfrastructureApi implements BasicInfrastructureApi {

    private final MultiBlockRegistry registry;
    private final MultiBlockLifecycleManager lifecycleManager;

    public MineplusBasicInfrastructureApi(
            MultiBlockRegistry registry,
            MultiBlockLifecycleManager lifecycleManager
    ) {
        this.registry = registry;
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public Collection<String> getTypeIds() {
        return registry.typeKeys();
    }

    @Override
    public Collection<MultiBlockInstance> getLoadedInstances() {
        return registry.getInstances();
    }

    @Override
    public MultiBlockInstance getAt(Location location) {
        if (location == null) {
            return null;
        }
        return registry.getByLocation(BlockCoordinate.from(location));
    }

    @Override
    public MultiBlockInstance get(UUID id) {
        return registry.getInstance(id);
    }

    @Override
    public MultiBlockInstance createAndPlace(String typeId, Location location, UUID owner, Player actor) {
        if (actor == null) {
            return null;
        }

        UUID creator = actor.getUniqueId();
        MultiBlockInstance instance = lifecycleManager.create(typeId, location, owner, creator, new Quaternionf());
        if (instance == null) {
            return null;
        }

        if (!lifecycleManager.place(instance.id(), actor)) {
            lifecycleManager.remove(instance.id(), actor, false);
            return null;
        }

        return instance;
    }

    @Override
    public boolean removeAt(Location location, Player actor, boolean destroy) {
        MultiBlockInstance instance = getAt(location);
        if (instance == null) {
            return false;
        }
        return lifecycleManager.remove(instance.id(), actor, destroy);
    }
}
