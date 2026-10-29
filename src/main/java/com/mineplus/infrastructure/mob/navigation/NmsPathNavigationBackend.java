package com.mineplus.infrastructure.mob.navigation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The reflective NMS backend for servers without the Paper Pathfinder API.
 * Two channels, both through the server's own pathfinding:
 * <ol>
 *   <li><b>Brain walk target</b> â€” for Brain-driven mobs (villagers) the
 *       vanilla {@code WALK_TARGET} memory is set, so the mob's own AI
 *       performs the walk (hazard-aware, no goal tug-of-war).</li>
 *   <li><b>Path navigation</b> â€” {@code PathNavigation.moveTo(x, y, z,
 *       speed)} for goal-driven mobs and as the fallback everywhere.</li>
 * </ol>
 *
 * <p>Handles come from {@link NmsEntityPlan}; {@code canFloat} is configured
 * once per mob so long marches survive river crossings.
 */
final class NmsPathNavigationBackend implements NavigationBackend {

    /** One-time water-float setup per mob, so paths survive river crossings. */
    private static final java.util.Set<UUID> floatConfigured = ConcurrentHashMap.newKeySet();

    NmsPathNavigationBackend() {
    }

    @Override
    public boolean available() {
        // The NMS runtime is always present; per-entity plans decide success.
        return true;
    }

    /** First choice for Brain-driven mobs: their own AI performs the walk. */
    @Override
    public int preference(org.bukkit.entity.Mob mob) {
        return mob instanceof Villager ? 0 : 10;
    }

    @Override
    public boolean moveTo(Mob mob, Location target, double speed) {
        NmsEntityPlan plan = NmsEntityPlan.forEntity(mob);
        if (plan == null || plan.getHandle == null) {
            return false;
        }
        // Both channels path through the mob's own navigation, so both need the
        // float flag: a long march crosses a river, and a villager whose
        // navigation refuses water stands on the bank forever.
        configureFloat(plan, mob);
        if (mob instanceof Villager && BrainWalkTarget.apply(plan, mob, target, speed)) {
            return true;
        }
        return moveToViaNavigation(plan, mob, target, speed);
    }

    /** Sets {@code canFloat} once per mob, for whichever channel walks it. */
    private static void configureFloat(NmsEntityPlan plan, Mob mob) {
        if (plan.navigationSetCanFloat == null || plan.getNavigation == null
                || !floatConfigured.add(mob.getUniqueId())) {
            return;
        }
        try {
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return;
            }
            Object navigation = plan.getNavigation.invoke(handle);
            if (navigation != null) {
                plan.navigationSetCanFloat.invoke(navigation, true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stop(Mob mob) {
        try {
            NmsEntityPlan plan = NmsEntityPlan.forEntity(mob);
            if (plan == null || plan.getHandle == null) {
                return;
            }
            if (mob instanceof Villager) {
                BrainWalkTarget.clear(plan, mob);
            }
            if (plan.getNavigation == null || plan.navigationStop == null) {
                return;
            }
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return;
            }
            Object navigation = plan.getNavigation.invoke(handle);
            if (navigation != null) {
                plan.navigationStop.invoke(navigation);
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

    private boolean moveToViaNavigation(NmsEntityPlan plan, Mob mob, Location target, double speed) {
        if (plan == null || plan.navigationMoveTo == null) {
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
            return Boolean.TRUE.equals(
                    plan.navigationMoveTo.invoke(navigation, target.getX(), target.getY(), target.getZ(), speed));
        } catch (Throwable failure) {
            return false;
        }
    }
}

