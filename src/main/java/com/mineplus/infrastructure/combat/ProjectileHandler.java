package com.mineplus.infrastructure.combat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * The per-fire hook a module attaches to the ballistics engine.
 *
 * <p>Every method has a usable default, so a module that only needs "simulate a
 * round and damage what it hits" passes {@link #STANDARD} and writes nothing.
 * The engine emits the muzzle flash, tracers and impact feedback itself; a
 * handler that wants a bespoke look overrides the notification it cares about.
 *
 * <p>Callbacks run on the server thread inside the simulation step, so they may
 * touch the world and apply damage directly — no scheduling, and no
 * read-then-write race against the round that is being resolved.
 */
public interface ProjectileHandler {

    /** Targeting, damage and default visuals: the behavior a plain gun wants. */
    ProjectileHandler STANDARD = new ProjectileHandler() {
    };

    /**
     * Whether a round may hit {@code candidate}. The default accepts any living,
     * valid, not-dead entity that is not the shooter; modules tighten it here
     * (players only, no passive wildlife, no invulnerable bosses).
     */
    default boolean allowTarget(Entity candidate, Player shooter) {
        if (!(candidate instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return false;
        }
        return shooter == null || !candidate.getUniqueId().equals(shooter.getUniqueId());
    }

    /** Called once per launched round, before it starts flying. */
    default void onLaunch(Player shooter, Location origin, Vector direction, BallisticProfile profile) {
    }

    /**
     * Called for a round that struck a living target, before damage is applied.
     *
     * @return {@code false} to withhold damage (a non-lethal round, a friendly
     *         target, a shield parry the module implements itself)
     */
    default boolean onEntityHit(ProjectileHit hit) {
        return true;
    }

    /** Called for a round that struck geometry. */
    default void onBlockHit(ProjectileHit hit) {
    }

    /** Called when a round simply expires at the end of its range. */
    default void onExpired(Player shooter, Location position, BallisticProfile profile) {
    }
}
