package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Removes a mob's vanilla behavior without removing its ability to move.
 *
 * <p>The distinction matters: {@code LivingEntity#setAI(false)} also stops
 * {@code Mob#aiStep} from ticking the path navigation, so a mob with AI
 * disabled ignores every path the Core computes. What "no vanilla behavior"
 * actually means is "no vanilla goals": the goal and target selectors are
 * emptied (reflectively — Bukkit exposes no API for them) while AI stays
 * enabled, so vanilla path following still walks the Core's paths and vanilla
 * physics, gravity and knockback still apply.</p>
 *
 * <p>Goals can come back at runtime — a villager that panics, a mob that is
 * tempted — so callers re-assert isolation periodically, not only on spawn.</p>
 *
 * <p>Thread model: main thread only (entity access is).</p>
 */
public final class MobAiIsolation {

    private MobAiIsolation() {
    }

    /**
     * Clears {@code entity}'s vanilla goals and target, keeping AI enabled.
     *
     * @return {@code true} when the entity's selectors were reached and
     *         cleared; {@code false} for a Brain-driven entity (a villager) or
     *         a runtime whose selectors could not be resolved — vanilla AI is
     *         then still active and the caller should report it rather than
     *         assume isolation
     */
    public static boolean isolate(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || entity.isDead() || !entity.isValid()) {
            return false;
        }
        mob.setAware(true);
        mob.setTarget(null);
        NmsEntityPlan plan = NmsEntityPlan.forEntity(entity);
        if (plan == null) {
            return false;
        }
        try {
            Object handle = plan.getHandle.invoke(entity);
            if (handle == null) {
                return false;
            }
            return plan.clearGoals(handle);
        } catch (Throwable unavailable) {
            // A runtime whose reflection surface changed loses AI isolation,
            // not the whole custom-mob subsystem.
            return false;
        }
    }

    /**
     * {@code true} when this entity type exposes a goal/target selector — the
     * structural requirement for isolation. Brain-driven entities report
     * {@code false}.
     */
    public static boolean isSupported(LivingEntity entity) {
        NmsEntityPlan plan = NmsEntityPlan.forEntity(entity);
        return plan != null && (plan.goalSelectorGetter != null || plan.targetSelectorGetter != null);
    }

    /** The entity's head yaw, falling back to its body yaw when unreadable. */
    public static float headYaw(LivingEntity entity, float fallback) {
        return read(entity, fallback, true);
    }

    /** The entity's head pitch, or {@code fallback} when unreadable. */
    public static float pitch(LivingEntity entity, float fallback) {
        return read(entity, fallback, false);
    }

    private static float read(LivingEntity entity, float fallback, boolean headYaw) {
        if (entity == null) {
            return fallback;
        }
        NmsEntityPlan plan = NmsEntityPlan.forEntity(entity);
        if (plan == null) {
            return fallback;
        }
        try {
            Object handle = plan.getHandle.invoke(entity);
            if (handle == null) {
                return fallback;
            }
            return headYaw ? plan.headYaw(handle, fallback) : plan.pitch(handle, fallback);
        } catch (Throwable unavailable) {
            return fallback;
        }
    }
}
