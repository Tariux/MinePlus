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

    /** What one isolation attempt actually reached. */
    public enum Result {
        /** Both vanilla goal selectors were emptied. */
        GOALS,
        /** The Brain (a villager-family entity) was emptied; selectors are absent. */
        BRAIN,
        /** Selectors and Brain both emptied. */
        GOALS_AND_BRAIN,
        /** Nothing was emptied: vanilla AI keeps running and the caller must say so. */
        UNREACHABLE,
        /** Not a pathfinding mob (an item, a player, a projectile). */
        NOT_A_MOB,
        /**
         * Isolation deliberately skipped: the definition asked to keep the
         * donor entity's own goals, so vanilla AI is expected, not a defect.
         */
        GOALS_KEPT;

        public boolean isolated() {
            return this == GOALS || this == BRAIN || this == GOALS_AND_BRAIN;
        }

        /** A short label for status output. */
        public String label() {
            return switch (this) {
                case GOALS -> "goals";
                case BRAIN -> "brain";
                case GOALS_AND_BRAIN -> "goals+brain";
                case UNREACHABLE -> "NOT ISOLATED";
                case NOT_A_MOB -> "not a mob";
                case GOALS_KEPT -> "vanilla goals kept";
            };
        }
    }

    private MobAiIsolation() {
    }

    /**
     * Clears {@code entity}'s vanilla goals and target, keeping AI enabled.
     * Also clears the Brain for Brain-driven entities (villagers, piglins, etc.)
     * when the goal/target selectors are unavailable.
     *
     * @return {@code true} when the entity's selectors or Brain were reached and
     *         cleared; {@code false} when the entity type is unsupported or
     *         the runtime's reflection surface changed — vanilla AI is then
     *         still active and the caller should report it rather than assume
     *         isolation
     */
    public static boolean isolate(LivingEntity entity) {
        return isolateDetailed(entity).isolated();
    }

    /**
     * The same isolation as {@link #isolate}, reported per mechanism so a
     * caller can tell "isolated through the Brain" from "nothing was reachable"
     * — the latter means vanilla AI is still driving the mob and has to be
     * surfaced, never swallowed.
     */
    public static Result isolateDetailed(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || entity.isDead() || !entity.isValid()) {
            return Result.NOT_A_MOB;
        }
        mob.setAware(true);
        mob.setTarget(null);
        NmsEntityPlan plan = NmsEntityPlan.forEntity(entity);
        if (plan == null) {
            return Result.UNREACHABLE;
        }
        boolean goals;
        boolean brain;
        try {
            Object handle = plan.getHandle.invoke(entity);
            if (handle == null) {
                return Result.UNREACHABLE;
            }
            goals = plan.clearGoals(handle);
            brain = plan.clearBrain(handle);
        } catch (Throwable unavailable) {
            return Result.UNREACHABLE;
        }
        if (goals && brain) {
            return Result.GOALS_AND_BRAIN;
        }
        if (goals) {
            return Result.GOALS;
        }
        if (brain) {
            return Result.BRAIN;
        }
        return Result.UNREACHABLE;
    }

    /**
     * {@code true} when this entity type can be isolated: goal/target selectors
     * OR a Brain is available — Brain-driven entities (villagers) qualify through
     * the Brain path even when selectors are absent.
     */
    public static boolean isSupported(LivingEntity entity) {
        NmsEntityPlan plan = NmsEntityPlan.forEntity(entity);
        if (plan == null) {
            return false;
        }
        return plan.goalSelectorGetter != null || plan.targetSelectorGetter != null
                || plan.getBrain != null;
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
