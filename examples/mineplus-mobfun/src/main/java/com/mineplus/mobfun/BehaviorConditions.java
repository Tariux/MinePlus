package com.mineplus.mobfun;

import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Villager;

/**
 * Shared {@code /mobfun} profile conditions. Kept module-side (instead of in
 * the Core's BehaviorContext#Conditions) to show modules composing their own
 * gates on top of the Core engine — including situational ones like danger
 * detection and target sighting that need entity searches, not just block
 * context.
 */
final class BehaviorConditions {

    private BehaviorConditions() {
    }

    /** Baby mobs skip the behavior (growth churn would fight the showcase). */
    static Predicate<com.mineplus.infrastructure.mob.BehaviorContext> adult() {
        return context -> !(context.entity() instanceof org.bukkit.entity.Ageable ageable) || ageable.isAdult();
    }

    /**
     * True while the entity is in danger: any hostile ({@link Monster}) within
     * the radius, or the entity below half of its max health. This is the gate
     * for the guardian builders — they only raise a golem when actually
     * threatened.
     */
    static Predicate<com.mineplus.infrastructure.mob.BehaviorContext> inDanger(double radius) {
        return context -> {
            LivingEntity self = context.entity();
            if (self.isDead() || !self.isValid()) {
                return false;
            }
            AttributeInstance maxHealth = self.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            double maxHealthValue = maxHealth == null ? 20.0 : maxHealth.getValue();
            if (self.getHealth() < maxHealthValue * 0.5) {
                return true;
            }
            for (Entity entity : self.getNearbyEntities(radius, radius, radius)) {
                if (entity instanceof Monster) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * True when the entity can see a villager within the radius (line of sight
     * checked). This is the gate for the necromancer witch: no villager in
     * view, no summons.
     */
    static Predicate<com.mineplus.infrastructure.mob.BehaviorContext> seesVillager(double radius) {
        return context -> {
            LivingEntity self = context.entity();
            if (self.isDead() || !self.isValid()) {
                return false;
            }
            for (Entity entity : self.getNearbyEntities(radius, radius, radius)) {
                if (entity instanceof Villager && self.hasLineOfSight(entity)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Prey or predator: true when either a villager is in sight (within
     * {@code villagerRadius}) or a golem is close (within {@code golemRadius}).
     * This is the witch's exhibited trigger - she hunts villagers, and holds
     * her ground (potions flying) when a golem closes in.
     */
    static Predicate<com.mineplus.infrastructure.mob.BehaviorContext> threatOrPrey(double villagerRadius,
                                                                                   double golemRadius) {
        double searchRadius = Math.max(villagerRadius, golemRadius);
        double villagerRadiusSquared = villagerRadius * villagerRadius;
        double golemRadiusSquared = golemRadius * golemRadius;
        return context -> {
            LivingEntity self = context.entity();
            if (self.isDead() || !self.isValid()) {
                return false;
            }
            Location selfLocation = self.getLocation();
            for (Entity entity : self.getNearbyEntities(searchRadius, searchRadius, searchRadius)) {
                double distanceSquared = entity.getLocation().distanceSquared(selfLocation);
                if (entity instanceof Villager && distanceSquared <= villagerRadiusSquared
                        && self.hasLineOfSight(entity)) {
                    return true;
                }
                if (entity instanceof org.bukkit.entity.Golem && distanceSquared <= golemRadiusSquared) {
                    return true;
                }
            }
            return false;
        };
    }
}
