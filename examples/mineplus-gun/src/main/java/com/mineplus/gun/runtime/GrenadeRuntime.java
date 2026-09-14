package com.mineplus.gun.runtime;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.fx.ParticleFx;
import com.mineplus.gun.fx.SoundFx;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Thrown-grenade runtime: gravity + voxel bounce, fuse countdown, then a
 * line-of-sight radius explosion with distance falloff. Terrain damage honours
 * {@code settings.block-damage} and is bounded so a large blast radius cannot
 * become a world-edit storm.
 */
public final class GrenadeRuntime {

    /** Hard cap on terrain blocks changed per detonation. */
    private static final int MAX_TERRAIN_BLOCKS = 2048;
    /** Terrain is only ever edited within this radius, regardless of blast radius. */
    private static final double MAX_TERRAIN_RADIUS = 6.0;

    private final Supplier<GunConfig> config;
    private final ParticleFx fx;
    private final SoundFx sound;
    private final List<Grenade> grenades = new ArrayList<>();

    public GrenadeRuntime(Supplier<GunConfig> config, ParticleFx fx, SoundFx sound) {
        this.config = config;
        this.fx = fx;
        this.sound = sound;
    }

    public int activeGrenades() {
        return grenades.size();
    }

    public void clear() {
        grenades.clear();
    }

    public void throwGrenade(Player thrower, WeaponDefinition weapon, Location origin, Vector velocity) {
        grenades.add(new Grenade(thrower.getUniqueId(), weapon, origin.getWorld(),
                origin.clone(), velocity.clone(), weapon.grenadeFuseTicks(), weapon.grenadeBounces()));
    }

    public void tick() {
        if (grenades.isEmpty()) {
            return;
        }
        for (Grenade grenade : grenades) {
            if (!grenade.alive) {
                continue;
            }
            advance(grenade);
            if (--grenade.fuse <= 0) {
                detonate(grenade);
                grenade.alive = false;
            }
        }
        grenades.removeIf(grenade -> !grenade.alive);
    }

    private void advance(Grenade grenade) {
        World world = grenade.world;
        if (world == null) {
            grenade.alive = false;
            return;
        }
        grenade.velocity.setY(grenade.velocity.getY() - 0.045);
        Location position = grenade.position;

        double nextX = position.getX() + grenade.velocity.getX();
        if (solid(world, nextX, position.getY(), position.getZ())) {
            grenade.velocity.setX(-grenade.velocity.getX() * 0.5);
        } else {
            position.setX(nextX);
        }

        double nextY = position.getY() + grenade.velocity.getY();
        if (solid(world, position.getX(), nextY, position.getZ())) {
            if (grenade.bouncesLeft > 0) {
                grenade.bouncesLeft--;
                grenade.velocity.setY(-grenade.velocity.getY() * 0.4);
            } else {
                grenade.velocity.setY(0);
                grenade.velocity.setX(grenade.velocity.getX() * 0.6);
                grenade.velocity.setZ(grenade.velocity.getZ() * 0.6);
            }
        } else {
            position.setY(nextY);
        }

        double nextZ = position.getZ() + grenade.velocity.getZ();
        if (solid(world, position.getX(), position.getY(), nextZ)) {
            grenade.velocity.setZ(-grenade.velocity.getZ() * 0.5);
        } else {
            position.setZ(nextZ);
        }
    }

    private static boolean solid(World world, double x, double y, double z) {
        Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return !block.isPassable();
    }

    private void detonate(Grenade grenade) {
        World world = grenade.world;
        if (world == null) {
            return;
        }
        Location center = grenade.position;
        WeaponDefinition weapon = grenade.weapon;
        double radius = weapon.grenadeRadius();
        double maxDamage = weapon.grenadeDamage();

        // Line-of-sight falloff: a wall shadows the blast.
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity entity) || !entity.isValid() || entity.isDead()) {
                continue;
            }
            double distance = entity.getLocation().distance(center);
            if (distance > radius) {
                continue;
            }
            if (blocked(world, center, entity.getEyeLocation(), distance)) {
                continue;
            }
            double factor = Math.max(0.0, 1.0 - (distance / radius));
            double damage = maxDamage * factor * factor;
            if (damage <= 0) {
                continue;
            }
            Player owner = org.bukkit.Bukkit.getPlayer(grenade.ownerId);
            if (owner != null) {
                entity.damage(damage, owner);
            } else {
                entity.damage(damage);
            }
            Vector knockback = entity.getLocation().toVector().subtract(center.toVector());
            if (knockback.lengthSquared() > 0) {
                entity.setVelocity(entity.getVelocity().add(knockback.normalize().multiply(factor * 0.8)));
            }
        }

        GunConfig current = config.get();
        if (current != null && current.settings().blockDamage()) {
            damageTerrain(center, Math.min(radius, MAX_TERRAIN_RADIUS));
        }

        fx.smokeRing(center, Math.min(radius, 6.0));
        sound.play(center, "mineplusgun:shot", 1.0f, 0.6f);
    }

    private boolean blocked(World world, Location from, Location to, double distance) {
        Vector direction = to.toVector().subtract(from.toVector());
        if (direction.lengthSquared() < 1.0e-6) {
            return false;
        }
        direction.normalize();
        return world.rayTraceBlocks(from, direction, distance, org.bukkit.FluidCollisionMode.NEVER, true) != null;
    }

    private void damageTerrain(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int r = (int) Math.ceil(radius);
        int changed = 0;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        double radiusSquared = radius * radius;
        for (int x = -r; x <= r && changed < MAX_TERRAIN_BLOCKS; x++) {
            for (int y = -r; y <= r && changed < MAX_TERRAIN_BLOCKS; y++) {
                for (int z = -r; z <= r && changed < MAX_TERRAIN_BLOCKS; z++) {
                    if (x * x + y * y + z * z > radiusSquared) {
                        continue;
                    }
                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (block.getType().isAir() || unbreakable(block.getType())) {
                        continue;
                    }
                    block.setType(Material.AIR);
                    changed++;
                }
            }
        }
    }

    private static boolean unbreakable(Material material) {
        return material == Material.BEDROCK
                || material == Material.BARRIER
                || material == Material.STRUCTURE_VOID
                || material == Material.STRUCTURE_BLOCK
                || material == Material.END_PORTAL_FRAME;
    }

    private static final class Grenade {
        final UUID ownerId;
        final WeaponDefinition weapon;
        final World world;
        final Location position;
        final Vector velocity;
        int fuse;
        int bouncesLeft;
        boolean alive = true;

        Grenade(UUID ownerId, WeaponDefinition weapon, World world, Location position, Vector velocity,
                int fuse, int bouncesLeft) {
            this.ownerId = ownerId;
            this.weapon = weapon;
            this.world = world;
            this.position = position;
            this.velocity = velocity;
            this.fuse = fuse;
            this.bouncesLeft = bouncesLeft;
        }
    }
}
