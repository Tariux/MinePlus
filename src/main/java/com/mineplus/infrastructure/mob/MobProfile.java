package com.mineplus.infrastructure.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.entity.EntityType;

/**
 * Immutable behavior set registered for one {@link EntityType} or for one
 * custom-mob identity. A profile is an ordered list of action entries: at most
 * one action fires per engine evaluation (first eligible wins), mirroring the
 * reference mod's single-action-per-event dispatch. Conditions gate the whole
 * profile.
 *
 * <p>Several modules can profile the same entity type. The engine dispatches
 * their profiles in a fixed order — {@code priority} descending, then
 * registration order ascending — so a profile's position never depends on
 * which module happened to toggle a feature last.
 *
 * <p>A profile built with a {@code customId} applies <b>only</b> to entities
 * carrying that custom identity, and an identity's profiles never mix with the
 * vanilla type's profiles: an Evil Pig cannot inherit ordinary pig behavior, and
 * an ordinary pig can never pick up an Evil Pig's profile.
 *
 * <p>Build via {@link #builder(EntityType)} or
 * {@link #builder(EntityType, String)}; register through
 * {@link MobBehaviorApi#register}.
 */
public final class MobProfile {

    private static final Pattern CUSTOM_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** One action plus its expected average interval in seconds between fires. */
    public record ActionEntry(BehaviorAction action, double intervalSeconds) {
    }

    private final EntityType entityType;
    /** Custom identity this profile belongs to, or {@code null} for vanilla-type dispatch. */
    private final String customId;
    private final int priority;
    private final List<ActionEntry> actions;
    private final List<Predicate<BehaviorContext>> conditions;
    /** Stamped once by the engine at registration; the tie-break of the dispatch order. */
    private long sequence;

    private MobProfile(EntityType entityType, String customId, int priority, List<ActionEntry> actions,
                       List<Predicate<BehaviorContext>> conditions) {
        this.entityType = entityType;
        this.customId = customId;
        this.priority = priority;
        this.actions = List.copyOf(actions);
        this.conditions = List.copyOf(conditions);
    }

    public EntityType entityType() {
        return entityType;
    }

    /**
     * The custom identity ({@code namespace:path}) this profile is bound to, or
     * {@code null} when it applies to every entity of {@link #entityType()}.
     */
    public String customId() {
        return customId;
    }

    /** {@code true} when this profile is bound to a custom identity rather than a vanilla type. */
    public boolean isCustom() {
        return customId != null;
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

    /** A profile for every entity of {@code entityType}. */
    public static Builder builder(EntityType entityType) {
        return new Builder(entityType, null);
    }

    /**
     * A profile for entities carrying the {@code namespace:path} custom
     * identity. The type is still required: it is the identity's base entity
     * and keeps identity checks cheap (a profile is only ever consulted for the
     * type an entity actually is).
     */
    public static Builder builder(EntityType entityType, String customId) {
        return new Builder(entityType, normalize(customId));
    }

    private static String normalize(String customId) {
        if (customId == null) {
            return null;
        }
        String normalized = customId.toLowerCase(Locale.ROOT);
        if (!CUSTOM_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("custom mob id must be 'namespace:path': " + customId);
        }
        return normalized;
    }

    public static final class Builder {
        private final EntityType entityType;
        private final String customId;
        private int priority;
        private final List<ActionEntry> actions = new ArrayList<>();
        private final List<Predicate<BehaviorContext>> conditions = new ArrayList<>();

        private Builder(EntityType entityType, String customId) {
            if (entityType == null || !entityType.isAlive() || !entityType.isSpawnable()) {
                throw new IllegalArgumentException("MobProfile requires a living, spawnable entity type: " + entityType);
            }
            this.entityType = entityType;
            this.customId = customId;
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
                throw new IllegalStateException("MobProfile for "
                        + (customId == null ? entityType : customId) + " has no actions");
            }
            return new MobProfile(entityType, customId, priority, actions, conditions);
        }
    }
}
