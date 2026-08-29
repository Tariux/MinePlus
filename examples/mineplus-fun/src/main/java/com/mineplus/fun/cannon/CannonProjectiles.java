package com.mineplus.fun.cannon;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.util.Vector;

/**
 * Shared cannon projectile launcher and explosion calibration.
 *
 * <p>Two projectile types, chosen by ammunition: TNT (ballistic arc, explodes on
 * fuse) and fire charges (straight-line {@link LargeFireball}, explodes on
 * impact, no fire). Fireball speed uses its own, lower calibration because
 * fireballs are gravity-free and never drop.
 *
 * <p>Explosion intensity: vanilla TNT power 4 reads as overkill for a fun
 * module, so cannon-launched TNT is tagged and its explosion is re-issued at a
 * reduced power. Cancelling {@link EntityExplodeEvent} and calling
 * {@code createExplosion(loc, power, ...)} is re-entrancy safe because the
 * replacement explosion carries no source entity, so it cannot re-enter this
 * handler.
 */
public final class CannonProjectiles implements Listener {

    /** Scoreboard tag marking cannon-launched TNTPrimed entities. */
    public static final String CANNON_SHOT_TAG = "mineplusfun_cannon_shot";

    /** Explosion power for cannon TNT; vanilla block-TNT is 4.0. */
    private static final float CANNON_EXPLOSION_POWER = 2.2F;

    /** Speed scaling for fireballs relative to TNT-class speeds (they fly straight, so they feel faster). */
    private static final double FIREBALL_SPEED_FACTOR = 0.45D;

    /** Listener instance; the launch helpers are static. */
    public CannonProjectiles() {
    }

    /**
     * Launches a cannon projectile at {@code point} flying along {@code direction}.
     *
     * @param fireball true to launch a fire charge (straight shot, impact detonation);
     *                 false to launch TNTPrimed (ballistic arc, fuse detonation)
     */
    public static Entity launch(World world, Location point, Vector direction, double speed, Player source, boolean fireball) {
        Vector velocity = direction.clone().normalize().multiply(speed);

        if (fireball) {
            LargeFireball projectile = world.spawn(point, LargeFireball.class);
            projectile.setShooter(source);
            projectile.setIsIncendiary(false);
            // Fireballs fly by acceleration; setDirection drives it (setVelocity alone
            // does not move them).
            projectile.setDirection(velocity.clone().multiply(FIREBALL_SPEED_FACTOR));
            return projectile;
        }

        TNTPrimed projectile = world.spawn(point, TNTPrimed.class);
        projectile.setSource(source);
        projectile.addScoreboardTag(CANNON_SHOT_TAG);
        projectile.setVelocity(velocity);
        return projectile;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed)
                || !event.getEntity().getScoreboardTags().contains(CANNON_SHOT_TAG)) {
            return;
        }

        event.setCancelled(true);
        World world = event.getLocation().getWorld();
        if (world != null) {
            world.createExplosion(event.getLocation(), CANNON_EXPLOSION_POWER, false, true);
        }
    }
}
