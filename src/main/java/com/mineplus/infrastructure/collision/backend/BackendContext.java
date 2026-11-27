package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

/**
 * World handle shared by the backends for one model instance: where its grid
 * origin sits, which world it belongs to, and the logging hook.
 */
public final class BackendContext {

    private final World world;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final Consumer<String> warn;

    public BackendContext(World world, double originX, double originY, double originZ,
            Consumer<String> warn) {
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.warn = warn == null ? message -> {
        } : warn;
    }

    public World world() {
        return world;
    }

    public Consumer<String> warn() {
        return warn;
    }

    /** Bottom-north-west corner of one cell. */
    public Location blockLocation(PlacedFurniture furniture, PlacedPart part) {
        return new Location(world,
                originX + part.cellX(),
                originY + part.cellY(),
                originZ + part.cellZ());
    }

    /** Cell centre, used for entities. */
    public Location centerLocation(PlacedFurniture furniture, PlacedPart part) {
        Location location = blockLocation(furniture, part);
        location.add(0.5, 0.5, 0.5);
        return location;
    }

    /** Parses a block state, or returns {@code null} when the server rejects it. */
    public BlockData parse(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException unknown) {
            warn.accept("block state not supported by this server: " + state);
            return null;
        }
    }

    /** Spawns a technical entity with the flags no technical entity may skip. */
    public Entity spawnTechnical(Location location, EntityType type) {
        Entity entity = world.spawnEntity(location, type);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setPersistent(false);
        entity.setGravity(false);
        return entity;
    }
}
