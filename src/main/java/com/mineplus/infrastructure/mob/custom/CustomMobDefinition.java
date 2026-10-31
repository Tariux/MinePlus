package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.MobProfile;
import com.mineplus.util.DebugLogger;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * A Mineplus custom mob: a vanilla base entity ({@code PIG}, {@code ZOMBIE},
 * …) carrying a Mineplus identity, a visual model, attributes and its own
 * behavior profile.
 *
 * <p><b>Isolation contract.</b> By default a custom mob never runs vanilla AI:
 * its goal and target selectors (or, for Brain-driven entities like villagers,
 * its Brain behaviors) are cleared on spawn, on every chunk restore, and again
 * on the manager's periodic re-assert pass, while AI stays enabled so the
 * Core's navigator can still tick the entity. Its behavior comes exclusively
 * from the profile built by {@link Builder#behavior(Consumer)} and executed by
 * the Core's mob behavior engine. {@link Builder#inheritGoalsFrom(EntityType)}
 * opts a definition out of that contract: the mob then keeps the vanilla goals
 * of its base type and is a vanilla brain wearing a custom face. Vanilla mobs
 * of the same base type are untouched either way — profiles for a custom id are
 * never visible to them and vice versa.</p>
 *
 * <p><b>Rendering contract.</b> {@link Builder#visual(VisualMode)} picks how
 * the mob looks. {@link VisualMode#AUTO} (the default) rigs the mob through
 * the pack subsystem when the server can present per-part models and leaves
 * the base entity visible when it cannot; {@link VisualMode#BASE} always shows
 * the vanilla silhouette and only publishes the pack assets, which is what a
 * texture-only reskin can do on a forward-looking server (a 1.21.1 client has
 * no entity-model override channel at all); {@link VisualMode#RIG} demands the
 * rig and falls back to the base only when the pack truly cannot present it.
 * Without a pack the base entity stays visible in every mode, so gameplay and
 * behavior still work.</p>
 *
 * <p>Build through {@link #builder(String, EntityType)} or
 * {@link #builder(String, EntityType, CustomMobModel)}; register through
 * {@link CustomMobApi#register(org.bukkit.plugin.Plugin, CustomMobDefinition)}.</p>
 */
public final class CustomMobDefinition {

    /** How the mob is presented. */
    public enum VisualMode {

        /** Vanilla silhouette only; the pack assets stay published for other users. */
        BASE,

        /** Per-part rig when the server can present it, base silhouette when it cannot. */
        AUTO,

        /** Rig whenever a model exists; a definition that cannot rig reports it. */
        RIG
    }

    private final String id;
    private final EntityType baseType;
    private final CustomMobModel model;
    private final CustomMobAttributes attributes;
    private final String displayName;
    private final boolean showName;
    private final boolean persistent;
    private final boolean hideBaseWhenRigged;
    private final boolean canPickupItems;
    private final VisualMode visual;
    private final EntityType goalDonor;
    private final Consumer<MobProfile.Builder> behavior;
    private final Consumer<LivingEntity> spawnConfigurator;

    private CustomMobDefinition(String id, EntityType baseType, CustomMobModel model,
                                CustomMobAttributes attributes, String displayName, boolean showName,
                                boolean persistent, boolean hideBaseWhenRigged, boolean canPickupItems,
                                VisualMode visual, EntityType goalDonor,
                                Consumer<MobProfile.Builder> behavior,
                                Consumer<LivingEntity> spawnConfigurator) {
        this.id = requireId(id);
        this.baseType = Objects.requireNonNull(baseType, "baseType");
        Class<?> entityClass = baseType.getEntityClass();
        if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) {
            throw new IllegalArgumentException("Custom mob base type must be a pathfinding Mob: " + baseType);
        }
        this.model = model;
        this.attributes = attributes == null ? CustomMobAttributes.none() : attributes;
        this.displayName = displayName == null || displayName.isBlank()
                ? prettify(path()) : displayName;
        this.showName = showName;
        this.persistent = persistent;
        this.hideBaseWhenRigged = hideBaseWhenRigged;
        this.canPickupItems = canPickupItems;
        this.visual = visual == null ? VisualMode.AUTO : visual;
        this.goalDonor = goalDonor;
        this.behavior = behavior;
        this.spawnConfigurator = spawnConfigurator;
    }

    private static String requireId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon == normalized.length() - 1) {
            throw new IllegalArgumentException("Custom mob id must be 'namespace:path': '" + value + "'");
        }
        String namespace = normalized.substring(0, colon);
        String path = normalized.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid custom mob namespace: '" + namespace + "'");
        }
        if (!path.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid custom mob path (use [a-z0-9_-]): '" + path + "'");
        }
        return namespace + ":" + path;
    }

    /** Full identity, e.g. {@code mobfun:evil_pig}. */
    public String id() {
        return id;
    }

    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    /** The id path segment, e.g. {@code evil_pig}. */
    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    /** The vanilla entity actually spawned and given the custom identity. */
    public EntityType baseType() {
        return baseType;
    }

    /** Visual model, or {@code null} for a behavior-only custom mob (vanilla look). */
    public CustomMobModel model() {
        return model;
    }

    public CustomMobAttributes attributes() {
        return attributes;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the display name floats above the mob. */
    public boolean showName() {
        return showName;
    }

    /** Whether the entity survives chunk/server restarts (default true). */
    public boolean persistent() {
        return persistent;
    }

    /** Whether the base entity is hidden once a rig covers it (default true). */
    public boolean hideBaseWhenRigged() {
        return hideBaseWhenRigged;
    }

    public boolean canPickupItems() {
        return canPickupItems;
    }

    /** How the mob is presented; never {@code null}. */
    public VisualMode visual() {
        return visual;
    }

    /** True when the definition asked to keep its base type's vanilla goals. */
    public boolean keepsVanillaGoals() {
        return goalDonor != null;
    }

    /** The donor type whose vanilla goals this mob keeps, or {@code null} when it is isolated. */
    public EntityType goalDonor() {
        return goalDonor;
    }

    /** Optional profile customizer; the manager supplies the type and custom id. */
    public Consumer<MobProfile.Builder> behavior() {
        return behavior;
    }

    /** Optional last-word configurator run after the Core applied identity and attributes. */
    public Consumer<LivingEntity> spawnConfigurator() {
        return spawnConfigurator;
    }

    public static Builder builder(String id, EntityType baseType) {
        return new Builder(id, baseType, null);
    }

    public static Builder builder(String id, EntityType baseType, CustomMobModel model) {
        return new Builder(id, baseType, model);
    }

    private static String prettify(String path) {
        String[] words = path.split("[_-]");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    public static final class Builder {

        private final String id;
        private final EntityType baseType;
        private final CustomMobModel model;
        private CustomMobAttributes attributes = CustomMobAttributes.none();
        private String displayName = "";
        private boolean showName;
        private boolean persistent = true;
        private boolean hideBaseWhenRigged = true;
        private boolean canPickupItems;
        private VisualMode visual = VisualMode.AUTO;
        private EntityType goalDonor;
        private Consumer<MobProfile.Builder> behavior;
        private Consumer<LivingEntity> spawnConfigurator;

        private Builder(String id, EntityType baseType, CustomMobModel model) {
            this.id = id;
            this.baseType = baseType;
            this.model = model;
        }

        public Builder attributes(CustomMobAttributes value) {
            this.attributes = value == null ? CustomMobAttributes.none() : value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value == null ? "" : value;
            return this;
        }

        public Builder showName(boolean value) {
            this.showName = value;
            return this;
        }

        /** Whether the mob survives chunk unloads and server restarts (default true). */
        public Builder persistent(boolean value) {
            this.persistent = value;
            return this;
        }

        /** Whether the base entity is hidden once a visual rig is active (default true). */
        public Builder hideBaseWhenRigged(boolean value) {
            this.hideBaseWhenRigged = value;
            return this;
        }

        /** How the mob is presented (default {@link VisualMode#AUTO}). */
        public Builder visual(VisualMode value) {
            this.visual = value == null ? VisualMode.AUTO : value;
            return this;
        }

        /**
         * Keeps the base type's vanilla goals: the Core then does not isolate
         * this mob, so it behaves like the donor it names while carrying its
         * custom identity, model and profile actions. Use for content that is
         * "a real zombie that merely looks like the trickster"; leave unset for
         * a mob whose every movement comes from its own behavior profile.
         *
         * <p>The donor must be the mob's base type — a mob cannot inherit the
         * goals of a type it is not, so a mismatched donor is ignored and the
         * isolation contract stays in force.</p>
         */
        public Builder inheritGoalsFrom(EntityType donor) {
            this.goalDonor = donor == null || donor != baseType ? null : donor;
            if (donor != null && donor != baseType) {
                DebugLogger.warning("[CustomMobs] '" + id + "' asked to inherit the goals of '" + donor
                        + "' but its base type is '" + baseType + "'; the mob stays isolated.");
            }
            return this;
        }

        public Builder canPickupItems(boolean value) {
            this.canPickupItems = value;
            return this;
        }

        /**
         * Builds the mob's single behavior profile. The manager supplies the
         * base entity type and the custom id; the consumer adds actions,
         * priority and conditions. A custom mob without behavior stands still
         * (vanilla AI is cleared regardless).
         */
        public Builder behavior(Consumer<MobProfile.Builder> value) {
            this.behavior = value;
            return this;
        }

        /** Runs after identity, attributes and isolation; use for module-specific setup. */
        public Builder spawnConfigurator(Consumer<LivingEntity> value) {
            this.spawnConfigurator = value;
            return this;
        }

        public CustomMobDefinition build() {
            return new CustomMobDefinition(id, baseType, model, attributes, displayName, showName,
                    persistent, hideBaseWhenRigged, canPickupItems, visual, goalDonor,
                    behavior, spawnConfigurator);
        }
    }
}
