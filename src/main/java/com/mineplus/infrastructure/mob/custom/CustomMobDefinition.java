package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.MobProfile;
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
 * <p><b>Isolation contract.</b> A custom mob never runs vanilla AI: its goal
 * and target selectors are cleared on spawn and on every chunk restore, while
 * AI stays enabled so the Core's navigator can still tick the entity. Its
 * behavior comes exclusively from the profile built by
 * {@link Builder#behavior(Consumer)} and executed by the Core's mob behavior
 * engine. Vanilla mobs of the same base type are untouched — profiles for a
 * custom id are never visible to them and vice versa.</p>
 *
 * <p><b>Rendering contract.</b> When a {@link CustomMobModel} is supplied and
 * the pack subsystem is available, the base entity is hidden and the model is
 * rendered as per-part {@code ItemDisplay}s driven by the Core. Without a pack
 * the base entity stays visible, so gameplay and behavior still work.</p>
 *
 * <p>Build through {@link #builder(String, EntityType)} or
 * {@link #builder(String, EntityType, CustomMobModel)}; register through
 * {@link CustomMobApi#register(org.bukkit.plugin.Plugin, CustomMobDefinition)}.</p>
 */
public final class CustomMobDefinition {

    private final String id;
    private final EntityType baseType;
    private final CustomMobModel model;
    private final CustomMobAttributes attributes;
    private final String displayName;
    private final boolean showName;
    private final boolean persistent;
    private final boolean hideBaseWhenRigged;
    private final boolean canPickupItems;
    private final Consumer<MobProfile.Builder> behavior;
    private final Consumer<LivingEntity> spawnConfigurator;

    private CustomMobDefinition(String id, EntityType baseType, CustomMobModel model,
                                CustomMobAttributes attributes, String displayName, boolean showName,
                                boolean persistent, boolean hideBaseWhenRigged, boolean canPickupItems,
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
                    persistent, hideBaseWhenRigged, canPickupItems, behavior, spawnConfigurator);
        }
    }
}
