package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface BasicInfrastructureApi {

    Collection<String> getTypeIds();

    Collection<MultiBlockInstance> getLoadedInstances();

    MultiBlockInstance getAt(Location location);

    MultiBlockInstance get(UUID id);

    MultiBlockInstance createAndPlace(String typeId, Location location, UUID owner, Player actor);

    boolean removeAt(Location location, Player actor, boolean destroy);
}
