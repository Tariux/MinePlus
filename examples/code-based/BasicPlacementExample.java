package com.mineplus.examples;

import com.mineplus.infrastructure.core.api.BasicInfrastructureApi;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class BasicPlacementExample {

    private final BasicInfrastructureApi api;

    public BasicPlacementExample(BasicInfrastructureApi api) {
        this.api = api;
    }

    public MultiBlockInstance createAtPlayerTarget(Player actor, String typeId, Location location) {
        UUID owner = actor.getUniqueId();
        return api.createAndPlace(typeId, location, owner, actor);
    }

    public boolean removeAt(Location location, Player actor) {
        return api.removeAt(location, actor, true);
    }

    public MultiBlockInstance inspect(Location location) {
        return api.getAt(location);
    }
}
