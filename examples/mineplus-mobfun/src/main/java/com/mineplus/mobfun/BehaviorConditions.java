package com.mineplus.mobfun;

import java.util.function.Predicate;

/**
 * Shared {@code /mobfun} profile conditions. Kept module-side (instead of in
 * the Core's BehaviorContext#Conditions) to show modules composing their own
 * gates on top of the Core engine.
 */
final class BehaviorConditions {

    private BehaviorConditions() {
    }

    /** Baby mobs skip the behavior (growth churn would fight the showcase). */
    static Predicate<com.mineplus.infrastructure.mob.BehaviorContext> adult() {
        return context -> !(context.entity() instanceof org.bukkit.entity.Ageable ageable) || ageable.isAdult();
    }
}
