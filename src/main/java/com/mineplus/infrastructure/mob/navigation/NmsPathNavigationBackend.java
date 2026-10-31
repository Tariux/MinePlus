package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The reflective NMS backend for servers without the Paper Pathfinder API.
 *
 * <p>Every mob family — goal-driven and Brain-driven alike — walks through the
 * same vanilla {@code NavigationAbstract}: {@code moveTo(x, y, z, speed)}
 * requests a path and the mob's own step follows it. The Brain is deliberately
 * not used as a movement channel: a custom mob has its Brain behaviors cleared
 * by design, so a walk target written into a cleared Brain would never be
 * consumed.</p>
 *
 * <p>Because the mob's vanilla goals are cleared, nothing else rotates it.
 * Each request therefore also points the vanilla look control at the
 * destination, so the body yaw follows the path instead of freezing at the
 * spawn direction (the "walks backwards" symptom). Handles come from
 * {@link NmsEntityPlan}; {@code canFloat} is configured once per mob so long
 * marches survive river crossings.</p>
 */
final class NmsPathNavigationBackend implements NavigationBackend {

    NmsPathNavigationBackend() {
    }

    @Override
    public boolean available() {
        // The NMS runtime is always present; per-entity plans decide success.
        return true;
    }

    /** Always the fallback: Paper's public Pathfinder wins when it is present. */
    @Override
    public int preference(org.bukkit.entity.Mob mob) {
        return 10;
    }

    @Override
    public boolean moveTo(Mob mob, Location target, double speed) {
        NmsEntityPlan plan = NmsEntityPlan.forEntity(mob);
        if (plan == null || plan.getHandle == null) {
            return false;
        }
        try {
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return false;
            }
            Object navigation = plan.getNavigation == null ? null : plan.getNavigation.invoke(handle);
            if (navigation == null) {
                return false;
            }
            // Cheap enough to re-assert per request and stateless: a mob that
            // was unloaded and reloaded gets its water crossing back without a
            // UUID registry that grows for the life of the server.
            plan.setCanFloat(navigation, true);
            return plan.moveTo(handle, navigation, target, speed);
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    public void stop(Mob mob) {
        try {
            NmsEntityPlan plan = NmsEntityPlan.forEntity(mob);
            if (plan == null || plan.getHandle == null) {
                return;
            }
            if (plan.getNavigation == null) {
                return;
            }
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return;
            }
            Object navigation = plan.getNavigation.invoke(handle);
            if (navigation != null) {
                plan.stopNavigation(navigation);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean wakeUp(Villager villager) {
        try {
            NmsEntityPlan plan = NmsEntityPlan.forEntity(villager);
            if (plan == null || plan.stopSleeping == null) {
                return false;
            }
            Object handle = plan.getHandle.invoke(villager);
            if (handle == null) {
                return false;
            }
            plan.stopSleeping.invoke(handle);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
