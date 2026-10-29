package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The Paper Pathfinder backend: {@code Mob#getPathfinder()#moveTo} â€” the
 * clean public pathing API on Paper servers.
 *
 * <p>The call site is compiled against the Paper API but only linked after a
 * {@code Class.forName} probe succeeds, so a Spigot server (whose
 * {@code Mob} interface has no {@code getPathfinder}) never links this class.
 */
final class PaperPathfinderBackend implements NavigationBackend {

    private static final boolean AVAILABLE = detect();

    PaperPathfinderBackend() {
    }

    @Override
    public boolean available() {
        return AVAILABLE;
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
        try {
            var pathfinder = mob.getPathfinder();
            // Vanilla's own mob navigation opens doors and floats in water;
            // Paper's Pathfinder defaults both off, which is why a mob given a
            // destination behind a village door used to stop at the door.
            pathfinder.setCanOpenDoors(true);
            pathfinder.setCanPassDoors(true);
            pathfinder.setCanFloat(true);
            return pathfinder.moveTo(target, speed);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Asks the pathfinder for a real path and whether it reaches the target. */
    @Override
    public boolean canReach(Mob mob, Location target) {
        try {
            var result = mob.getPathfinder().findPath(target);
            return result != null && result.canReachFinalPoint();
        } catch (Throwable failure) {
            return true; // unknown, never veto a walk on a probe failure
        }
    }

    @Override
    public void stop(Mob mob) {
        try {
            mob.getPathfinder().stopPathfinding();
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

    private static boolean detect() {
        try {
            Class.forName("com.destroystokyo.paper.entity.Pathfinder");
            return true;
        } catch (ClassNotFoundException spigotServer) {
            return false;
        }
    }
}

