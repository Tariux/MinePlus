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
 *       without the Paper API: the vanilla {@code NavigationAbstract} path
 *       request, which every mob family (goal-driven or Brain-driven) follows
 *       through its own step. Handles resolve by shape, so one jar serves both
 *       mojang- and Spigot-mapped runtimes.</li>
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
        boolean walked = walk(BACKENDS[paperFirst ? 0 : 1], mob, target, speed)
                || walk(BACKENDS[paperFirst ? 1 : 0], mob, target, speed);
        if (walked) {
            // A goal-less mob has nothing pointing its head along the path, so
            // the navigator does it: without this the body yaw stays where the
            // mob spawned and a rig looks like it is walking sideways/backwards.
            lookToward(mob, target);
        }
        return walked;
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
        if (lookAt != null) {
            try {
                lookAt.invoke(mob, target);
                return;
            } catch (Throwable unavailable) {
                // Fall through to the NMS look control.
            }
        }
        NmsEntityPlan plan = NmsEntityPlan.forEntity(mob);
        if (plan == null || plan.getHandle == null) {
            return;
        }
        try {
            Object handle = plan.getHandle.invoke(mob);
            plan.faceTarget(handle, target.getX(), target.getY() + 1.0, target.getZ(), 20.0f, 20.0f);
        } catch (Throwable unavailable) {
            // Vanilla head tracking is a fine fallback.
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
     * Drops any current path; a stopped mob stays where it is (its vanilla AI
     * goals are cleared, so nothing re-paths it behind the caller's back).
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

