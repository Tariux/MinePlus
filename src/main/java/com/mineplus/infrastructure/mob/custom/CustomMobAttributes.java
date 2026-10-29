package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import org.bukkit.entity.LivingEntity;

/**
 * Optional attribute overrides applied to a custom mob when it spawns or is
 * restored from a chunk. Unset fields keep the vanilla base entity's attributes,
 * so a definition only states what makes it different.
 *
 * <p>Health is handled explicitly: setting {@code MAX_HEALTH} alone would leave
 * the entity at its previous health, so a custom mob with a larger pool spawns
 * wounded. The definition applies the new maximum and then fills health.</p>
 */
public final class CustomMobAttributes {

    private final Double maxHealth;
    private final Double movementSpeed;
    private final Double attackDamage;
    private final Double followRange;
    private final Double armor;
    private final Double knockbackResistance;

    private CustomMobAttributes(Double maxHealth, Double movementSpeed, Double attackDamage,
                                Double followRange, Double armor, Double knockbackResistance) {
        this.maxHealth = positiveOrNull(maxHealth);
        this.movementSpeed = positiveOrNull(movementSpeed);
        this.attackDamage = nonNegativeOrNull(attackDamage);
        this.followRange = positiveOrNull(followRange);
        this.armor = nonNegativeOrNull(armor);
        this.knockbackResistance = nonNegativeOrNull(knockbackResistance);
    }

    public static CustomMobAttributes none() {
        return new CustomMobAttributes(null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
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
        if (movementSpeed != null) {
            MobAttributes.set(entity, "MOVEMENT_SPEED", movementSpeed);
        }
        if (attackDamage != null) {
            MobAttributes.set(entity, "ATTACK_DAMAGE", attackDamage);
        }
        if (followRange != null) {
            MobAttributes.set(entity, "FOLLOW_RANGE", followRange);
        }
        if (armor != null) {
            MobAttributes.set(entity, "ARMOR", armor);
        }
        if (knockbackResistance != null) {
            MobAttributes.set(entity, "KNOCKBACK_RESISTANCE", knockbackResistance);
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

        private Builder() {
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

        public CustomMobAttributes build() {
            return new CustomMobAttributes(maxHealth, movementSpeed, attackDamage,
                    followRange, armor, knockbackResistance);
        }
    }
}
