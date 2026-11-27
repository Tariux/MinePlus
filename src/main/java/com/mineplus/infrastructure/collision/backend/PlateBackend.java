package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;

/**
 * Thin rideable platforms from boats or minecarts.
 *
 * <p>Optional and off by default: a boat carries a box the player can stand on
 * but also can push, ride and break, so it only suits controlled areas such as
 * minigames. When the option is off, the manager routes these parts to real
 * blocks instead.
 */
public final class PlateBackend implements CollisionBackend {

    private final boolean enabled;
    private final boolean minecart;
    private final CollisionBackend fallback;

    public PlateBackend(boolean enabled, boolean minecart, CollisionBackend fallback) {
        this.enabled = enabled;
        this.minecart = minecart;
        this.fallback = fallback;
    }

    @Override
    public BackendType type() {
        return BackendType.PLATE;
    }

    @Override
    public boolean supports(PlacedPart part) {
        return enabled || fallback.supports(part);
    }

    @Override
    public int cost(PlacedPart part) {
        return 6;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (!enabled) {
            fallback.apply(context, furniture, part);
            return;
        }
        if (part.entityId() != null && Bukkit.getEntity(part.entityId()) != null) {
            return;
        }
        Location location = context.centerLocation(furniture, part);
        Entity spawned = context.spawnTechnical(location,
                minecart ? EntityType.MINECART : EntityType.BOAT);
        spawned.setCustomNameVisible(false);
        if (spawned instanceof Minecart cart) {
            cart.setSlowWhenEmpty(true);
        }
        part.setEntityType(spawned.getType().name());
        part.setEntityId(spawned.getUniqueId());
    }

    @Override
    public void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        if (!enabled) {
            fallback.remove(context, furniture, part);
            return;
        }
        if (part.entityId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(part.entityId());
        if (entity != null) {
            entity.remove();
        }
        part.setEntityId(null);
    }
}
