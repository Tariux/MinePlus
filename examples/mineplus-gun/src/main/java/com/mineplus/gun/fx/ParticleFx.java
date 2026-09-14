package com.mineplus.gun.fx;

import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.util.DebugLogger;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Bounded particle effects for firing: muzzle flash, sparse tracer and impact
 * puff. A per-tick budget (from {@code ballistics.max-particles-per-tick})
 * degrades gracefully — over-budget emissions are skipped, never queued.
 */
public final class ParticleFx {

    private volatile int maxPerTick;
    private int usedThisTick;

    public ParticleFx(int maxPerTick) {
        this.maxPerTick = Math.max(0, maxPerTick);
    }

    public void updateBudget(int maxPerTick) {
        this.maxPerTick = Math.max(0, maxPerTick);
    }

    /** Called once per tick by the runtime before any emission. */
    public void beginTick() {
        usedThisTick = 0;
    }

    public void muzzle(Location location, WeaponDefinition weapon) {
        if (!reserve(2)) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Particle particle = particle(weapon.muzzleParticle(), Particle.SMOKE);
        world.spawnParticle(particle, location, 3, 0.03, 0.03, 0.03, 0.0);
    }

    /**
     * A sparse tracer: one puff every {@code step} blocks along the resolved
     * path, so a long shot costs a bounded, config-tunable number of particles.
     */
    public void tracer(Location from, Location to, WeaponDefinition weapon, double step) {
        World world = from.getWorld();
        if (world == null || to.getWorld() == null || to.getWorld() != world) {
            return;
        }
        double distance = from.distance(to);
        if (distance < 0.5) {
            return;
        }
        double stride = Math.max(1.0, step);
        int puffs = (int) Math.min(16, Math.ceil(distance / stride));
        if (puffs <= 0) {
            return;
        }
        Particle particle = particle(weapon.tracerParticle(), Particle.CRIT);
        for (int i = 1; i <= puffs; i++) {
            if (!reserve(1)) {
                return;
            }
            double t = i / (double) puffs;
            Location point = from.clone().add(
                    (to.getX() - from.getX()) * t,
                    (to.getY() - from.getY()) * t,
                    (to.getZ() - from.getZ()) * t);
            world.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public void impact(Location location, WeaponDefinition weapon) {
        if (!reserve(6)) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Particle particle = particle(weapon.impactParticle(), Particle.CRIT);
        world.spawnParticle(particle, location, 6, 0.1, 0.1, 0.1, 0.01);
    }

    /** Rising smoke ring used by grenade detonations. */
    public void smokeRing(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int points = (int) Math.max(8, Math.min(48, Math.round(radius * 12)));
        for (int i = 0; i < points; i++) {
            if (!reserve(1)) {
                return;
            }
            double angle = (2 * Math.PI * i) / points;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, x, center.getY() + 0.2, z, 1, 0.0, 0.02, 0.0, 0.0);
        }
        world.spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private boolean reserve(int count) {
        if (usedThisTick + count > maxPerTick) {
            return false;
        }
        usedThisTick += count;
        return true;
    }

    /** Tolerant particle lookup: an unknown name falls back to a safe default. */
    public static Particle particle(String name, Particle fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            DebugLogger.warning("[MineplusGun] Unknown particle '" + name + "'; using " + fallback + ".");
            return fallback;
        }
    }
}
