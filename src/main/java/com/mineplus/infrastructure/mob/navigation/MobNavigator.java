package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The Core's movement facility: sends a {@link Mob} walking toward a target
 * through the best pathfinding backend the running server offers.
 *
 * <p>Backends live in this package and are tried in order:
 * <ol>
 *   <li>{@link PaperPathfinderBackend} â€” the clean public Pathfinder API on
 *       Paper servers. Compiled against the Paper API but only linked after a
 *       {@code Class.forName} probe, so a Spigot server never reaches the
 *       call site (the Core's standard paper-only seam).</li>
 *   <li>{@link NmsPathNavigationBackend} â€” reflective fallback for servers
 *       without the Paper API: the vanilla Brain walk-target channel for
 *       Brain-driven mobs (villagers), then NMS {@code PathNavigation}.
 *       Every 1.20.5+ runtime is mojang-mapped, so the named reflection
 *       lookups hit; a structural probe covers exotic remaps.</li>
 * </ol>
 *
 * <p>A {@code false} return from {@link #moveTo} means no backend could path
 * (or none is available on this server flavor); the caller decides what
 * standing still means for its feature. The navigator never teleports and
 * never spawns anything â€” movement is always the entity's own vanilla walk.
 *
 * <p>Thread model: calls must happen on the owning entity's tick thread (the
 * behavior engine enforces this); reflection handles resolve lazily on first
 * use and are cached, so steady-state navigation costs one {@code invoke}
 * per call.
 */
public final class MobNavigator {

    private MobNavigator() {
    }

    /** Movement-speed modifier handed to the vanilla navigation (1.0 = normal walk). */
    public static final double NORMAL_SPEED = 1.0;

    /** Recalculating a long path every tick is waste; this is the sane floor. */
    public static final long REPATH_COOLDOWN_TICKS = 30L;

    private static final NavigationBackend[] BACKENDS = {
            new PaperPathfinderBackend(),
            new NmsPathNavigationBackend()
    };

    /**
     * Navigates {@code mob} toward {@code target} at {@code speedModifier}
     * (1.0 = the mob's normal walk speed). Re-issuing the call for the same
     * target is cheap and expected â€” callers pace it with
     * {@link #REPATH_COOLDOWN_TICKS}.
     *
     * @return {@code true} when movement started (or is already in progress)
     *         toward the target
     */
    public static boolean moveTo(Mob mob, Location target, double speedModifier) {
        if (mob == null || mob.isDead() || !mob.isValid()
                || target == null || target.getWorld() == null
                || mob.getWorld() != target.getWorld()) {
            return false;
        }
        double speed = Math.max(0.1, Math.min(4.0, speedModifier <= 0 ? NORMAL_SPEED : speedModifier));
        // Each backend names the mob family it speaks to; try the one that owns
        // this mob first so nothing sets a path the mob's own AI will fight.
        boolean paperFirst = BACKENDS[0].preference(mob) <= BACKENDS[1].preference(mob);
        return walk(BACKENDS[paperFirst ? 0 : 1], mob, target, speed)
                || walk(BACKENDS[paperFirst ? 1 : 0], mob, target, speed);
    }

    private static boolean walk(NavigationBackend backend, Mob mob, Location target, double speed) {
        return backend.available() && backend.moveTo(mob, target, speed);
    }

    /**
     * Navigates {@code mob} toward {@code target} but no closer than
     * {@code stopDistance} â€” the standard "walk there, stop when close" march
     * primitive. Targets already within the stop distance stop the walker.
     */
    public static boolean moveToWithin(Mob mob, Location target, double stopDistance, double speedModifier) {
        if (mob == null || target == null || mob.getWorld() == null || target.getWorld() == null) {
            return false;
        }
        if (mob.getLocation().distance(target) <= stopDistance) {
            stop(mob);
            return true;
        }
        return moveTo(mob, target, speedModifier);
    }

    /**
     * Whether the server can compute a path from {@code mob} to {@code target}
     * that actually reaches it.
     *
     * <p>This is a real path search, not a distance check — use it to validate a
     * destination worth paying for (a journey's final goal, a raid target), not
     * on every repath. Backends without the query answer {@code true}, so a
     * server that cannot tell never has a walk vetoed.
     */
    public static boolean canReach(Mob mob, Location target) {
        if (mob == null || mob.isDead() || !mob.isValid()
                || target == null || target.getWorld() == null
                || mob.getWorld() != target.getWorld()) {
            return false;
        }
        boolean paperFirst = BACKENDS[0].preference(mob) <= BACKENDS[1].preference(mob);
        NavigationBackend backend = BACKENDS[paperFirst ? 0 : 1];
        if (!backend.available()) {
            backend = BACKENDS[paperFirst ? 1 : 0];
        }
        return !backend.available() || backend.canReach(mob, target);
    }

    /** The distance one path search may span for this mob (see {@link MobAttributes}). */
    public static double pathRange(Mob mob) {
        return MobAttributes.pathRange(mob);
    }

    /**
     * Turns the mob's head toward {@code target} without moving it — the
     * finishing touch on a waypoint change, so a mob that stops to gather looks
     * where it is going instead of at the wall it just avoided.
     *
     * <p>{@code Mob#lookAt} is Paper API; it is invoked reflectively so a Spigot
     * server simply keeps vanilla head tracking.
     */
    public static void lookToward(Mob mob, Location target) {
        if (mob == null || target == null || mob.getWorld() != target.getWorld()) {
            return;
        }
        java.lang.reflect.Method lookAt = LookSeam.LOOK_AT;
        if (lookAt == null) {
            return;
        }
        try {
            lookAt.invoke(mob, target);
        } catch (Throwable unavailable) {
            // A server whose Mob#lookAt has a different signature: vanilla
            // head tracking is a fine fallback.
        }
    }

    /** Lazily resolved Paper look control; {@code null} on Spigot. */
    private static final class LookSeam {

        private static final java.lang.reflect.Method LOOK_AT = find();

        private static java.lang.reflect.Method find() {
            try {
                return Mob.class.getMethod("lookAt", Location.class);
            } catch (Throwable missing) {
                return null;
            }
        }
    }

    /**
     * Drops any current path; a stopped mob returns to its own vanilla AI.
     * Both channels are cleared: a march may have been issued through either,
     * and a leftover Brain walk-target would silently keep the mob walking.
     */
    public static void stop(Mob mob) {
        if (mob == null) {
            return;
        }
        for (NavigationBackend backend : BACKENDS) {
            if (backend.available()) {
                backend.stop(mob);
            }
        }
    }

    /** Ends a villager's sleep state (dawn, camp break-up). */
    public static void wakeUp(Villager villager) {
        if (villager == null || !villager.isSleeping()) {
            return;
        }
        for (NavigationBackend backend : BACKENDS) {
            if (backend.available() && backend.wakeUp(villager)) {
                return;
            }
        }
    }
}

