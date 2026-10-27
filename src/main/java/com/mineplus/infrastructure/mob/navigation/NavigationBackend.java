package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * One movement backend of the {@link MobNavigator} facade: a way to make a
 * {@link Mob} walk somewhere with the server's own pathfinding.
 *
 * <p>Implementations are stateless singletons and must never throw: a backend
 * that cannot serve an entity simply returns {@code false} so the next
 * backend (or the caller) decides.
 */
interface NavigationBackend {

    /** {@code true} when this backend works on the running server flavor. */
    boolean available();

    /**
     * Order this backend is tried for one specific mob: lower wins. The default
     * keeps the declaration order.
     *
     * <p>This exists because two channels cannot both own one mob's movement:
     * a Brain-driven mob (villager) that gets a {@code PathNavigation} target
     * has its own AI fight it on its next brain tick, while a goal-driven mob
     * has no {@code WALK_TARGET} memory to write at all. Each backend claims
     * the mob family it actually speaks to.
     */
    default int preference(Mob mob) {
        return 0;
    }

    /**
     * Starts walking {@code mob} toward {@code target}.
     *
     * @param speed the movement-speed modifier (1.0 = the mob's normal walk)
     * @return {@code true} when a movement toward the target was started
     */
    boolean moveTo(Mob mob, Location target, double speed);

    /** Drops the mob's current path (no-op when nothing is walking). */
    void stop(Mob mob);

    /** Ends a villager's sleep state; {@code false} leaves the state alone. */
    boolean wakeUp(Villager villager);
}

