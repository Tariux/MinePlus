package com.mineplus.infrastructure.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.entity.EntityType;

/**
 * Immutable behavior set registered for one {@link EntityType}. A profile is
 * an ordered list of action entries: at most one action fires per engine
 * evaluation (first eligible wins), mirroring the reference mod's
 * single-action-per-event dispatch. Conditions gate the whole profile.
 *
 * <p>Several modules can profile the same entity type. The engine dispatches
 * their profiles in a fixed order — {@code priority} descending, then
 * registration order ascending — so a profile's position never depends on
 * which module happened to toggle a feature last.
 *
 * <p>Build via {@link #builder(EntityType)}; register through
 * {@link MobBehaviorApi#register}.
 */
public final class MobProfile {

    /** One action plus its expected average interval in seconds between fires. */
    public record ActionEntry(BehaviorAction action, double intervalSeconds) {
    }

    private final EntityType entityType;
    private final int priority;
    private final List<ActionEntry> actions;
    private final List<Predicate<BehaviorContext>> conditions;
    /** Stamped once by the engine at registration; the tie-break of the dispatch order. */
    private long sequence;

    private MobProfile(EntityType entityType, int priority, List<ActionEntry> actions,
                       List<Predicate<BehaviorContext>> conditions) {
        this.entityType = entityType;
        this.priority = priority;
        this.actions = List.copyOf(actions);
        this.conditions = List.copyOf(conditions);
    }

    public EntityType entityType() {
        return entityType;
    }

    /** Dispatch weight against other profiles of the same type; higher runs first. */
    public int priority() {
        return priority;
    }

    public List<ActionEntry> actions() {
        return actions;
    }

    /** All conditions must pass before any action of this profile is evaluated. */
    public List<Predicate<BehaviorContext>> conditions() {
        return conditions;
    }

    long sequence() {
        return sequence;
    }

    void stampSequence(long registrationSequence) {
        this.sequence = registrationSequence;
    }

    public static Builder builder(EntityType entityType) {
        return new Builder(entityType);
    }

    public static final class Builder {
        private final EntityType entityType;
        private int priority;
        private final List<ActionEntry> actions = new ArrayList<>();
        private final List<Predicate<BehaviorContext>> conditions = new ArrayList<>();

        private Builder(EntityType entityType) {
            if (entityType == null || !entityType.isAlive() || !entityType.isSpawnable()) {
                throw new IllegalArgumentException("MobProfile requires a living, spawnable entity type: " + entityType);
            }
            this.entityType = entityType;
        }

        /** A profile that outranks other modules' profiles of the same type when both match. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Appends an action expected to fire about once per {@code intervalSeconds}. */
        public Builder action(BehaviorAction action, double intervalSeconds) {
            if (action == null || intervalSeconds <= 0) {
                throw new IllegalArgumentException("action and positive intervalSeconds are required");
            }
            actions.add(new ActionEntry(action, intervalSeconds));
            return this;
        }

        /** Appends a profile-wide gate (all gates must pass for the profile to be evaluated). */
        public Builder condition(Predicate<BehaviorContext> condition) {
            if (condition != null) {
                conditions.add(condition);
            }
            return this;
        }

        public MobProfile build() {
            if (actions.isEmpty()) {
                throw new IllegalStateException("MobProfile for " + entityType + " has no actions");
            }
            return new MobProfile(entityType, priority, actions, conditions);
        }
    }
}
