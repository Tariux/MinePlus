package com.mineplus.gun.runtime;

import com.mineplus.gun.config.GunConfig;
import com.mineplus.gun.fx.ParticleFx;
import com.mineplus.gun.fx.SoundFx;
import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Tick-stepped, server-side simulated projectiles — one small step per bullet
 * per tick. Never spawns a projectile entity. A global cap
 * ({@code ballistics.max-active-bullets}) bounds the work; over-budget shots
 * resolve as a single instant trace instead of queueing.
 */
public final class BulletSimulator {

    private final Supplier<GunConfig> config;
    private final ParticleFx fx;
    private final SoundFx sound;
    private final List<Bullet> bullets = new ArrayList<>();

    private long totalShots;
    private long totalHits;
    private long instantTraces;

    public BulletSimulator(Supplier<GunConfig> config, ParticleFx fx, SoundFx sound) {
        this.config = config;
        this.fx = fx;
        this.sound = sound;
    }

    public int activeBullets() {
        return bullets.size();
    }

    public long totalShots() {
        return totalShots;
    }

    public long totalHits() {
        return totalHits;
    }

    public long instantTraces() {
        return instantTraces;
    }

    public void clear() {
        bullets.clear();
    }

    /**
     * Fires one round from {@code shooter} toward {@code direction} (already
     * normalized to the weapon's velocity in blocks/tick).
     */
    public void fire(Player shooter, WeaponDefinition weapon, Location origin, Vector direction) {
        totalShots++;
        GunConfig current = config.get();
        int max = current == null ? 96 : current.ballistics().maxActiveBullets();
        if (bullets.size() >= max) {
            // Graceful degradation: resolve immediately, never grow unbounded.
            instantTraces++;
            instantTrace(shooter, weapon, origin, direction);
            return;
        }
        Vector spread = applySpread(direction, weapon.spreadDegrees());
        bullets.add(new Bullet(shooter.getUniqueId(), weapon, origin.getWorld(), origin, spread));
    }

    /** Advances every live bullet one tick; finished bullets are removed. */
    public void tick() {
        if (bullets.isEmpty()) {
            return;
        }
        for (Bullet bullet : bullets) {
            if (bullet.alive()) {
                step(bullet);
            }
        }
        bullets.removeIf(bullet -> !bullet.alive());
    }

    private void step(Bullet bullet) {
        WeaponDefinition weapon = bullet.weapon();
        if (bullet.travelled() >= weapon.range()) {
            bullet.kill();
            return;
        }

        // Linear drag, then gravity, then the step this tick.
        Vector velocity = bullet.velocity().multiply(1.0 - weapon.drag());
        velocity.setY(velocity.getY() - weapon.gravity());
        bullet.velocity(velocity);

        Location start = bullet.position();
        double stepLength = velocity.length();
        if (stepLength < 1.0e-6) {
            bullet.kill();
            return;
        }
        Location end = start.clone().add(velocity);

        RayTraceResult blockHit = bullet.world().rayTraceBlocks(start, velocity, stepLength,
                FluidCollisionMode.NEVER, true);
        RayTraceResult entityHit = bullet.world().rayTraceEntities(start, velocity.clone().normalize(), stepLength,
                0.15, entity -> validTarget(entity, bullet.shooterId()));

        double blockDistance = distance(start, blockHit);
        double entityDistance = distance(start, entityHit);

        if (entityHit != null && (blockHit == null || entityDistance <= blockDistance)) {
            onEntityHit(bullet, entityHit);
        } else if (blockHit != null) {
            onBlockHit(bullet, blockHit);
        }

        if (!bullet.alive()) {
            return;
        }
        bullet.addTravelled(stepLength);
        bullet.position(end);
    }

    private void onEntityHit(Bullet bullet, RayTraceResult hit) {
        Entity target = hit.getHitEntity();
        if (!(target instanceof LivingEntity living)) {
            bullet.kill();
            return;
        }
        totalHits++;
        Location hitPosition = hit.getHitPosition().toLocation(bullet.world());
        fx.impact(hitPosition, bullet.weapon());

        boolean headshot = hit.getHitPosition().getY() >= living.getLocation().getY() + living.getHeight() * 0.75;
        double damage = bullet.weapon().damage() * falloff(bullet) * bullet.damageMultiplier();
        if (headshot) {
            damage *= bullet.weapon().headshotMultiplier();
        }

        Player shooter = player(bullet.shooterId());
        if (damage > 0) {
            if (shooter != null) {
                living.damage(damage, shooter);
            } else {
                living.damage(damage);
            }
        }

        if (bullet.penetrationLeft() > 0) {
            bullet.consumePenetration();
            bullet.multiplyDamage(0.7);
        } else {
            bullet.kill();
        }
    }

    private void onBlockHit(Bullet bullet, RayTraceResult hit) {
        Location hitPosition = hit.getHitPosition().toLocation(bullet.world());
        fx.impact(hitPosition, bullet.weapon());
        sound.play(hitPosition, "mineplusgun:shot", 0.4f, 1.6f);

        BlockFace face = hit.getHitBlockFace();
        boolean ricochet = face != null
                && bullet.ricochetLeft() > 0
                && ThreadLocalRandom.current().nextDouble() < bullet.weapon().ricochet();
        if (ricochet) {
            Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());
            if (normal.lengthSquared() > 0) {
                Vector reflected = bullet.velocity()
                        .subtract(normal.multiply(2.0 * bullet.velocity().dot(normal)))
                        .multiply(0.8);
                bullet.velocity(reflected);
                bullet.consumeRicochet();
                bullet.multiplyDamage(0.8);
                return;
            }
        }
        if (bullet.penetrationLeft() > 0) {
            bullet.consumePenetration();
            bullet.multiplyDamage(0.8);
            return;
        }
        bullet.kill();
    }

    /** Single-trace fallback used when the active-bullet budget is exhausted. */
    private void instantTrace(Player shooter, WeaponDefinition weapon, Location origin, Vector direction) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        Vector dir = applySpread(direction.clone().normalize(), weapon.spreadDegrees());
        double range = weapon.range();
        Location start = origin.clone();
        Location end = start.clone().add(dir.clone().multiply(range));
        fx.tracer(start, end, weapon, 4.0);

        RayTraceResult blockHit = world.rayTraceBlocks(start, dir, range, FluidCollisionMode.NEVER, true);
        RayTraceResult entityHit = world.rayTraceEntities(start, dir, range, 0.15,
                entity -> validTarget(entity, shooter.getUniqueId()));
        double blockDistance = distance(start, blockHit);
        double entityDistance = distance(start, entityHit);

        if (entityHit != null && (blockHit == null || entityDistance <= blockDistance)
                && entityHit.getHitEntity() instanceof LivingEntity living) {
            totalHits++;
            double damage = weapon.damage() * falloffDistance(weapon, entityDistance);
            boolean headshot = entityHit.getHitPosition().getY()
                    >= living.getLocation().getY() + living.getHeight() * 0.75;
            if (headshot) {
                damage *= weapon.headshotMultiplier();
            }
            if (damage > 0) {
                living.damage(damage, shooter);
            }
            fx.impact(entityHit.getHitPosition().toLocation(world), weapon);
            return;
        }
        if (blockHit != null) {
            fx.impact(blockHit.getHitPosition().toLocation(world), weapon);
        }
    }

    private double falloff(Bullet bullet) {
        return falloffDistance(bullet.weapon(), bullet.travelled());
    }

    private static double falloffDistance(WeaponDefinition weapon, double distance) {
        if (distance <= weapon.falloffStart()) {
            return 1.0;
        }
        double lost = (distance - weapon.falloffStart()) * weapon.falloffPerBlock();
        return Math.max(weapon.minDamageFraction(), 1.0 - lost);
    }

    private static double distance(Location start, RayTraceResult hit) {
        if (hit == null) {
            return Double.MAX_VALUE;
        }
        return start.toVector().distance(hit.getHitPosition());
    }

    private boolean validTarget(Entity entity, UUID shooterId) {
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return false;
        }
        if (entity.getUniqueId().equals(shooterId)) {
            return false;
        }
        GunConfig current = config.get();
        if (current != null && current.settings().pvpOnly() && !(entity instanceof Player)) {
            return false;
        }
        return true;
    }

    private Player player(UUID id) {
        return org.bukkit.Bukkit.getPlayer(id);
    }

    /** Random cone perturbation of a direction, in degrees. */
    private static Vector applySpread(Vector direction, double degrees) {
        if (degrees <= 0.0) {
            return direction;
        }
        double radians = Math.toRadians(degrees);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double ox = random.nextDouble(-radians, radians);
        double oy = random.nextDouble(-radians, radians);
        double oz = random.nextDouble(-radians, radians);
        Vector jittered = direction.clone().add(new Vector(ox, oy, oz)).normalize();
        return jittered.lengthSquared() > 0 ? jittered : direction;
    }
}
