package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.BehaviorContext;
import java.util.function.Predicate;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The {@code /mobfun} profile gates. Module-side on purpose — a showcase's
 * <i>situation</i> ("endangered", "prey in sight") is content, but every one of
 * them is composed here out of the Core's sensing primitives
 * ({@link BehaviorContext.Conditions}) instead of re-running the same radius
 * scans the engine already knows how to do, so a mob is never searched twice
 * for what one predicate can answer.
 */
final class BehaviorConditions {

    private BehaviorConditions() {
    }

    /**
     * Guardians hold their post. A builder the golem showcases tagged keeps its
     * ground instead of marching off to a village with the pilgrims - the two
     * showcases share the mob type, and this is what keeps them from stealing
     * each other's villagers.
     */
    static Predicate<BehaviorContext> notBuilder(Plugin plugin) {
        NamespacedKey builderKey = new NamespacedKey(plugin, GolemSummonAction.BUILDER_TAG_KEY);
        return context -> {
            Byte tagged = context.entity().getPersistentDataContainer()
                    .get(builderKey, PersistentDataType.BYTE);
            return tagged == null || tagged == 0;
        };
    }

    /**
     * The guardian builders' trigger: any hostile within the radius, or the mob
     * below half its own health. They only raise a golem when actually
     * threatened.
     */
    static Predicate<BehaviorContext> inDanger(double radius) {
        return BehaviorContext.Conditions.belowHealth(0.5)
                .or(BehaviorContext.Conditions.nearbyEntity(radius, entity -> entity instanceof Monster));
    }

    /**
     * Prey or predator: a villager in sight within {@code villagerRadius}, or a
     * golem within {@code threatRadius}. This is the witch's exhibited trigger —
     * she hunts villagers, and holds her ground (potions flying) when a golem
     * closes in.
     *
     * <p>The threat half is deliberately golem-only and matches what the action
     * reads out of the neighbourhood. Undead, illagers and other witches are
     * her allies: reacting to a {@code Monster} here made a necromancer treat
     * the mobs around her — including another necromancer — as enemies.
     */
    static Predicate<BehaviorContext> threatOrPrey(double villagerRadius, double threatRadius) {
        return BehaviorContext.Conditions.visibleEntity(villagerRadius, entity -> entity instanceof Villager)
                .or(BehaviorContext.Conditions.nearbyEntity(threatRadius,
                        entity -> entity instanceof Golem));
    }
}
