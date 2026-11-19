package com.mineplus.infrastructure.mob.navigation;

import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The Paper Pathfinder backend: {@code Mob#getPathfinder()#moveTo} — the clean
 * public pathing API on Paper servers.
 *
 * <p>The whole surface is reflective. The build compiles against the Spigot
 * stubs only, so {@code Mob#getPathfinder()} cannot be linked into the bytecode
 * and then explode as a {@code NoSuchMethodError} on a plain Spigot server. The
 * bridge resolves once, lazily, and stays {@code null} where Paper's pathfinder
 * does not exist, which makes {@link #available()} false and lets the
 * {@link NavigationBackend} chain fall through to the reflective NMS route.</p>
 */
final class PaperPathfinderBackend implements NavigationBackend {

    private static final Bridge BRIDGE = Bridge.resolve();

    PaperPathfinderBackend() {
    }

    @Override
    public boolean available() {
        return BRIDGE != null;
    }

    /**
     * A villager's Brain rewrites its own movement every brain tick, so a
     * Pathfinder target set on one gets fought — the NMS Brain walk-target
     * channel is the cooperative route for that family. Everyone else gets the
     * clean public API.
     */
    @Override
    public int preference(Mob mob) {
        return mob instanceof Villager ? 10 : 0;
    }

    @Override
    public boolean moveTo(Mob mob, Location target, double speed) {
        Bridge bridge = BRIDGE;
        if (bridge == null) {
            return false;
        }
        try {
            Object pathfinder = bridge.getPathfinder.invoke(mob);
            if (pathfinder == null) {
                return false;
            }
            // Vanilla's own mob navigation opens doors and floats in water;
            // Paper's Pathfinder defaults both off, which is why a mob given a
            // destination behind a village door used to stop at the door.
            bridge.setCanOpenDoors.invoke(pathfinder, true);
            bridge.setCanPassDoors.invoke(pathfinder, true);
            bridge.setCanFloat.invoke(pathfinder, true);
            return Boolean.TRUE.equals(bridge.moveTo.invoke(pathfinder, target, speed));
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Asks the pathfinder for a real path and whether it reaches the target. */
    @Override
    public boolean canReach(Mob mob, Location target) {
        Bridge bridge = BRIDGE;
        if (bridge == null) {
            return true;
        }
        try {
            Object pathfinder = bridge.getPathfinder.invoke(mob);
            if (pathfinder == null) {
                return true;
            }
            Object result = bridge.findPath.invoke(pathfinder, target);
            return result != null && Boolean.TRUE.equals(bridge.canReachFinalPoint.invoke(result));
        } catch (Throwable failure) {
            return true; // unknown, never veto a walk on a probe failure
        }
    }

    @Override
    public void stop(Mob mob) {
        Bridge bridge = BRIDGE;
        if (bridge == null) {
            return;
        }
        try {
            Object pathfinder = bridge.getPathfinder.invoke(mob);
            if (pathfinder != null) {
                bridge.stopPathfinding.invoke(pathfinder);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean wakeUp(Villager villager) {
        try {
            // wakeup(boolean) exists only on Paper's Villager surface.
            villager.getClass().getMethod("wakeup", boolean.class).invoke(villager, true);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** The reflected Paper pathfinder surface, resolved once or never. */
    private static final class Bridge {

        private final Method getPathfinder;
        private final Method setCanOpenDoors;
        private final Method setCanPassDoors;
        private final Method setCanFloat;
        private final Method moveTo;
        private final Method findPath;
        private final Method canReachFinalPoint;
        private final Method stopPathfinding;

        private Bridge(Method getPathfinder, Method setCanOpenDoors, Method setCanPassDoors,
                Method setCanFloat, Method moveTo, Method findPath, Method canReachFinalPoint,
                Method stopPathfinding) {
            this.getPathfinder = getPathfinder;
            this.setCanOpenDoors = setCanOpenDoors;
            this.setCanPassDoors = setCanPassDoors;
            this.setCanFloat = setCanFloat;
            this.moveTo = moveTo;
            this.findPath = findPath;
            this.canReachFinalPoint = canReachFinalPoint;
            this.stopPathfinding = stopPathfinding;
        }

        private static Bridge resolve() {
            try {
                Class<?> pathfinder = Class.forName("com.destroystokyo.paper.entity.Pathfinder");
                Class<?> pathResult = Class.forName("com.destroystokyo.paper.entity.PathResult");
                return new Bridge(
                        Mob.class.getMethod("getPathfinder"),
                        pathfinder.getMethod("setCanOpenDoors", boolean.class),
                        pathfinder.getMethod("setCanPassDoors", boolean.class),
                        pathfinder.getMethod("setCanFloat", boolean.class),
                        pathfinder.getMethod("moveTo", Location.class, double.class),
                        pathfinder.getMethod("findPath", Location.class),
                        pathResult.getMethod("canReachFinalPoint"),
                        pathfinder.getMethod("stopPathfinding"));
            } catch (Throwable spigotServer) {
                return null;
            }
        }
    }
}
