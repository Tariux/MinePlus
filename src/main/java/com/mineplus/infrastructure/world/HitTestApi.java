package com.mineplus.infrastructure.world;

import com.mineplus.infrastructure.mob.custom.CustomMobManager;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * One ray-trace against everything the Core owns. A module that shoots a ray
 * today has to ask vanilla for blocks, then separately ask whether a lattice
 * cell or a custom mob was in the way; this service does both in one pass and
 * labels the result.
 *
 * <p>Blocks collided through the vanilla ray are classified: a block that maps
 * to a virtual-model lattice cell reports {@link Hit#virtualModelId}, others
 * report plain {@code WORLD}. Entities hit are classified via
 * {@link CustomMobManager#isCustomMob(Entity)}.
 */
public final class HitTestApi {

    /** What the ray actually ran into. */
    public enum Kind { WORLD_BLOCK, VIRTUAL_MODEL_BLOCK, VANILLA_ENTITY, CUSTOM_MOB, MISS }

    /** One unified hit; {@code kind == MISS} for no contact. */
    public record Hit(Kind kind, Location location, Entity entity, String virtualModelId) {

        public static final Hit MISS = new Hit(Kind.MISS, null, null, null);

        public boolean isMiss() {
            return kind == Kind.MISS;
        }
    }

    private final VirtualBlockManager virtualBlocks;
    private final CustomMobManager customMobs;

    public HitTestApi(VirtualBlockManager virtualBlocks, CustomMobManager customMobs) {
        this.virtualBlocks = virtualBlocks;
        this.customMobs = customMobs;
    }

    /**
     * Traces one ray through blocks and entities and returns the nearest hit,
     * already classified. Both sub-traces run on the caller's thread; the
     * entity pass ignores non-living entities (display stacks included).
     */
    public Hit trace(Location start, Vector direction, double range) {
        if (start == null || direction == null || range <= 0 || direction.lengthSquared() == 0) {
            return Hit.MISS;
        }
        World world = start.getWorld();
        Vector dir = direction.clone().normalize();

        RayTraceResult blockHit = world.rayTraceBlocks(start, dir, range);
        RayTraceResult entityHit = world.rayTraceEntities(start, dir, range, 0.1, e -> e instanceof LivingEntity);

        double blockDist = blockHit == null || blockHit.getHitBlock() == null
                ? Double.MAX_VALUE : blockHit.getHitPosition().distance(start.toVector());
        double entityDist = entityHit == null || entityHit.getHitEntity() == null
                ? Double.MAX_VALUE : entityHit.getHitPosition().distance(start.toVector());

        if (blockDist == Double.MAX_VALUE && entityDist == Double.MAX_VALUE) {
            return Hit.MISS;
        }
        if (blockDist <= entityDist) {
            Location at = blockHit.getHitBlock().getLocation();
            var virtual = virtualBlocks.getVirtualBlockAt(at);
            if (virtual != null) {
                return new Hit(Kind.VIRTUAL_MODEL_BLOCK, at, null, virtual.modelName());
            }
            return new Hit(Kind.WORLD_BLOCK, at, null, null);
        }
        Entity entity = entityHit.getHitEntity();
        Location at = entity.getLocation();
        if (customMobs != null && customMobs.isCustomMob(entity)) {
            return new Hit(Kind.CUSTOM_MOB, at, entity, null);
        }
        return new Hit(Kind.VANILLA_ENTITY, at, entity, null);
    }
}
