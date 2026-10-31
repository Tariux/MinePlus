package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import com.mineplus.util.DebugLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * Optional attribute overrides applied to a custom mob when it spawns or is
 * restored from a chunk. Unset fields keep the vanilla base entity's attributes,
 * so a definition only states what makes it different.
 *
 * <p>Health is handled explicitly: setting {@code MAX_HEALTH} alone would leave
 * the entity at its previous health, so a custom mob with a larger pool spawns
 * wounded. The definition applies the new maximum and then fills health.</p>
 *
 * <p>Attribute templates capture a donor {@link EntityType}'s default attribute
 * values once per JVM lifetime, so a module can write
 * {@code CustomMobAttributes.fromTemplate(EntityType.IRON_GOLEM)} and get the
 * whole golem profile — then override the few fields it actually changes, which
 * always win over the template.</p>
 */
public final class CustomMobAttributes {

    /** Cache: EntityType -> donor type's default attributes. */
    private static final Map<EntityType, CustomMobAttributes> TEMPLATES = new ConcurrentHashMap<>();

    /** The attributes a template carries, in canonical ({@code GENERIC_}-less) names. */
    private static final Attribute[] TEMPLATE_ATTRIBUTES = {
            Attribute.GENERIC_MAX_HEALTH, Attribute.GENERIC_MOVEMENT_SPEED, Attribute.GENERIC_ATTACK_DAMAGE,
            Attribute.GENERIC_FOLLOW_RANGE, Attribute.GENERIC_ARMOR, Attribute.GENERIC_KNOCKBACK_RESISTANCE,
            Attribute.GENERIC_ATTACK_KNOCKBACK, Attribute.GENERIC_SCALE, Attribute.GENERIC_JUMP_STRENGTH,
            Attribute.GENERIC_GRAVITY,
    };

    private final Double maxHealth;
    private final Double movementSpeed;
    private final Double attackDamage;
    private final Double followRange;
    private final Double armor;
    private final Double knockbackResistance;
    private final Double attackKnockback;
    private final Double scale;
    private final Double jumpStrength;
    private final Double gravity;

    private CustomMobAttributes(Double maxHealth, Double movementSpeed, Double attackDamage,
                                Double followRange, Double armor, Double knockbackResistance,
                                Double attackKnockback, Double scale, Double jumpStrength, Double gravity) {
        this.maxHealth = positiveOrNull(maxHealth);
        this.movementSpeed = positiveOrNull(movementSpeed);
        this.attackDamage = nonNegativeOrNull(attackDamage);
        this.followRange = positiveOrNull(followRange);
        this.armor = nonNegativeOrNull(armor);
        this.knockbackResistance = nonNegativeOrNull(knockbackResistance);
        this.attackKnockback = nonNegativeOrNull(attackKnockback);
        this.scale = positiveOrNull(scale);
        this.jumpStrength = positiveOrNull(jumpStrength);
        this.gravity = positiveOrNull(gravity);
    }

    public static CustomMobAttributes none() {
        return new CustomMobAttributes(null, null, null, null, null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-filled with {@link #template(EntityType)}'s values, so a
     * definition writes only what it changes:
     * {@code fromTemplate(EntityType.ZOMBIE).movementSpeed(0.35).build()}.
     */
    public static CustomMobAttributes fromTemplate(EntityType type) {
        return builder().fromTemplate(type).build();
    }

    /**
     * The donor type's default attribute profile, captured once and cached for
     * the JVM lifetime; later calls for the same type return the same instance.
     *
     * <p>Spigot offers no way to materialise a living entity without adding it
     * to a world, so the donor is spawned at the world spawn point, read, and
     * removed again inside this call — it never exists across a tick boundary,
     * and it is spawned silent. A server that refuses the spawn (unknown type,
     * no loaded world) yields {@link #none()}, which overrides nothing: a mob
     * built on an unavailable template simply keeps its base attributes instead
     * of failing to register.</p>
     *
     * <p>Must run on the main thread (it spawns an entity); off-thread it
     * returns {@link #none()} without caching, so the real capture happens the
     * next time a main-thread caller asks.</p>
     */
    public static CustomMobAttributes template(EntityType type) {
        if (type == null) {
            return none();
        }
        if (!type.isAlive()) {
            return none();
        }
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            CustomMobAttributes cached = TEMPLATES.get(type);
            return cached == null ? none() : cached;
        }
        return TEMPLATES.computeIfAbsent(type, CustomMobAttributes::probeTemplate);
    }

    private static CustomMobAttributes probeTemplate(EntityType type) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().stream().findFirst().orElse(null);
        if (world == null) {
            return none();
        }
        org.bukkit.Location location = world.getSpawnLocation();
        CustomMobAttributes captured;
        try {
            Object donor = world.spawnEntity(location, type);
            if (!(donor instanceof LivingEntity living)) {
                if (donor instanceof org.bukkit.entity.Entity entity) {
                    entity.remove();
                }
                return none();
            }
            try {
                living.setSilent(true);
                captured = capture(living);
            } finally {
                living.remove();
            }
        } catch (Throwable refused) {
            DebugLogger.warning("[CustomMobs] Attribute template for '" + type
                    + "' could not be captured (" + refused + "); the mob keeps its base attributes.");
            return none();
        }
        return captured;
    }

    /** Reads every templated attribute off one donor entity's base values. */
    private static CustomMobAttributes capture(LivingEntity donor) {
        Builder builder = builder();
        put(builder, "MAX_HEALTH", attr(donor, Attribute.GENERIC_MAX_HEALTH));
        put(builder, "MOVEMENT_SPEED", attr(donor, Attribute.GENERIC_MOVEMENT_SPEED));
        put(builder, "ATTACK_DAMAGE", attr(donor, Attribute.GENERIC_ATTACK_DAMAGE));
        put(builder, "FOLLOW_RANGE", attr(donor, Attribute.GENERIC_FOLLOW_RANGE));
        put(builder, "ARMOR", attr(donor, Attribute.GENERIC_ARMOR));
        put(builder, "KNOCKBACK_RESISTANCE", attr(donor, Attribute.GENERIC_KNOCKBACK_RESISTANCE));
        put(builder, "ATTACK_KNOCKBACK", attr(donor, Attribute.GENERIC_ATTACK_KNOCKBACK));
        put(builder, "SCALE", attr(donor, Attribute.GENERIC_SCALE));
        put(builder, "JUMP_STRENGTH", attr(donor, Attribute.GENERIC_JUMP_STRENGTH));
        put(builder, "GRAVITY", attr(donor, Attribute.GENERIC_GRAVITY));
        return builder.build();
    }

    private static void put(Builder builder, String canonicalName, Double value) {
        if (value == null) {
            return;
        }
        switch (canonicalName) {
            case "MAX_HEALTH" -> builder.maxHealth(value);
            case "MOVEMENT_SPEED" -> builder.movementSpeed(value);
            case "ATTACK_DAMAGE" -> builder.attackDamage(value);
            case "FOLLOW_RANGE" -> builder.followRange(value);
            case "ARMOR" -> builder.armor(value);
            case "KNOCKBACK_RESISTANCE" -> builder.knockbackResistance(value);
            case "ATTACK_KNOCKBACK" -> builder.attackKnockback(value);
            case "SCALE" -> builder.scale(value);
            case "JUMP_STRENGTH" -> builder.jumpStrength(value);
            case "GRAVITY" -> builder.gravity(value);
            default -> {
            }
        }
    }

    private static Double attr(LivingEntity entity, Attribute key) {
        AttributeInstance instance = entity.getAttribute(key);
        if (instance == null) {
            return null;
        }
        double value = instance.getBaseValue();
        return Double.isFinite(value) ? value : null;
    }

    /** The template names this class can carry, for status reporting. */
    public static String[] templatedAttributeNames() {
        return new String[]{"MAX_HEALTH", "MOVEMENT_SPEED", "ATTACK_DAMAGE", "FOLLOW_RANGE", "ARMOR",
                "KNOCKBACK_RESISTANCE", "ATTACK_KNOCKBACK", "SCALE", "JUMP_STRENGTH", "GRAVITY"};
    }

    public Double maxHealth() {
        return maxHealth;
    }

    public Double movementSpeed() {
        return movementSpeed;
    }

    public Double attackDamage() {
        return attackDamage;
    }

    public Double followRange() {
        return followRange;
    }

    public Double armor() {
        return armor;
    }

    public Double knockbackResistance() {
        return knockbackResistance;
    }

    public Double attackKnockback() {
        return attackKnockback;
    }

    public Double scale() {
        return scale;
    }

    public Double jumpStrength() {
        return jumpStrength;
    }

    public Double gravity() {
        return gravity;
    }

    /** True when nothing is overridden (the mob keeps its base entity's profile). */
    public boolean isEmpty() {
        return maxHealth == null && movementSpeed == null && attackDamage == null && followRange == null
                && armor == null && knockbackResistance == null && attackKnockback == null
                && scale == null && jumpStrength == null && gravity == null;
    }

    /** How many attributes this profile sets. */
    public int overrideCount() {
        int count = 0;
        for (Attribute attribute : TEMPLATE_ATTRIBUTES) {
            String name = attribute.name().replace("GENERIC_", "");
            if (valueFor(name) != null) {
                count++;
            }
        }
        return count;
    }

    /** The override for one canonical attribute name, or {@code null} when unset. */
    public Double valueFor(String canonicalName) {
        return switch (canonicalName) {
            case "MAX_HEALTH" -> maxHealth;
            case "MOVEMENT_SPEED" -> movementSpeed;
            case "ATTACK_DAMAGE" -> attackDamage;
            case "FOLLOW_RANGE" -> followRange;
            case "ARMOR" -> armor;
            case "KNOCKBACK_RESISTANCE" -> knockbackResistance;
            case "ATTACK_KNOCKBACK" -> attackKnockback;
            case "SCALE" -> scale;
            case "JUMP_STRENGTH" -> jumpStrength;
            case "GRAVITY" -> gravity;
            default -> null;
        };
    }

    /** Applies every set attribute to a live entity. */
    public void applyTo(LivingEntity entity) {
        applyTo(entity, true);
    }

    /**
     * Applies every set attribute to a live entity.
     *
     * @param fillHealth {@code true} on a fresh spawn (a new maximum fills the
     *                   pool); {@code false} on a chunk restore, where the
     *                   entity's saved health is clamped to the new maximum
     *                   instead of being healed back to full
     */
    public void applyTo(LivingEntity entity, boolean fillHealth) {
        if (entity == null) {
            return;
        }
        if (maxHealth != null) {
            double previous = entity.getHealth();
            MobAttributes.set(entity, "MAX_HEALTH", maxHealth);
            double ceiling = MobAttributes.get(entity, "MAX_HEALTH", maxHealth);
            entity.setHealth(fillHealth
                    ? Math.max(1.0, ceiling)
                    : Math.max(1.0, Math.min(previous, ceiling)));
        }
        setIfPresent(entity, "MOVEMENT_SPEED", movementSpeed);
        setIfPresent(entity, "ATTACK_DAMAGE", attackDamage);
        setIfPresent(entity, "FOLLOW_RANGE", followRange);
        setIfPresent(entity, "ARMOR", armor);
        setIfPresent(entity, "KNOCKBACK_RESISTANCE", knockbackResistance);
        setIfPresent(entity, "ATTACK_KNOCKBACK", attackKnockback);
        setIfPresent(entity, "SCALE", scale);
        setIfPresent(entity, "JUMP_STRENGTH", jumpStrength);
        setIfPresent(entity, "GRAVITY", gravity);
    }

    private static void setIfPresent(LivingEntity entity, String name, Double value) {
        if (value != null) {
            MobAttributes.set(entity, name, value);
        }
    }

    private static Double positiveOrNull(Double value) {
        return value == null || value <= 0.0 ? null : value;
    }

    private static Double nonNegativeOrNull(Double value) {
        return value == null || value < 0.0 ? null : value;
    }

    public static final class Builder {

        private Double maxHealth;
        private Double movementSpeed;
        private Double attackDamage;
        private Double followRange;
        private Double armor;
        private Double knockbackResistance;
        private Double attackKnockback;
        private Double scale;
        private Double jumpStrength;
        private Double gravity;

        private Builder() {
        }

        /**
         * Copies a donor type's template into this builder. Fields set after
         * this call win, so a definition states its deviations last.
         */
        public Builder fromTemplate(EntityType type) {
            return from(template(type));
        }

        /** Copies one attribute profile into this builder. */
        public Builder from(CustomMobAttributes source) {
            if (source == null) {
                return this;
            }
            maxHealth = source.maxHealth;
            movementSpeed = source.movementSpeed;
            attackDamage = source.attackDamage;
            followRange = source.followRange;
            armor = source.armor;
            knockbackResistance = source.knockbackResistance;
            attackKnockback = source.attackKnockback;
            scale = source.scale;
            jumpStrength = source.jumpStrength;
            gravity = source.gravity;
            return this;
        }

        public Builder maxHealth(double value) {
            this.maxHealth = value;
            return this;
        }

        public Builder movementSpeed(double value) {
            this.movementSpeed = value;
            return this;
        }

        public Builder attackDamage(double value) {
            this.attackDamage = value;
            return this;
        }

        public Builder followRange(double value) {
            this.followRange = value;
            return this;
        }

        public Builder armor(double value) {
            this.armor = value;
            return this;
        }

        public Builder knockbackResistance(double value) {
            this.knockbackResistance = value;
            return this;
        }

        public Builder attackKnockback(double value) {
            this.attackKnockback = value;
            return this;
        }

        /** Model scale (1.0 is the base entity's own size). */
        public Builder scale(double value) {
            this.scale = value;
            return this;
        }

        public Builder jumpStrength(double value) {
            this.jumpStrength = value;
            return this;
        }

        public Builder gravity(double value) {
            this.gravity = value;
            return this;
        }

        public CustomMobAttributes build() {
            return new CustomMobAttributes(maxHealth, movementSpeed, attackDamage, followRange, armor,
                    knockbackResistance, attackKnockback, scale, jumpStrength, gravity);
        }
    }
}
