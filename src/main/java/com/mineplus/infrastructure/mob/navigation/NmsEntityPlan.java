package com.mineplus.infrastructure.mob.navigation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.entity.LivingEntity;

/**
 * The resolved NMS reflection plan for one CraftBukkit entity class: the
 * {@code getHandle()} bridge plus the vanilla navigation, Brain, goal-selector
 * and sleep handles the reflective backends operate through.
 *
 * <p>Plans are built lazily per CraftBukkit class and cached forever;
 * {@link #EMPTY} marks an unresolvable class so the probe never re-runs.
 * Lookups prefer mojang-mapped names (every 1.20.5+ runtime) and fall back to
 * a structural probe (return-type shape) for exotic remaps.
 */
final class NmsEntityPlan {

    /** Sentinel for "this CraftBukkit class cannot be bridged". */
    static final NmsEntityPlan EMPTY = new NmsEntityPlan(null, null, null, null, null, null, null,
            null, null, null, null, null, null);

    private static final Map<Class<?>, NmsEntityPlan> PLANS = new ConcurrentHashMap<>();
    private static final MethodHandle COLLECTION_CLEAR = clearHandle();
    private static final Predicate<Object> ALWAYS_TRUE = ignored -> true;

    final MethodHandle getHandle;
    final MethodHandle getNavigation;
    final MethodHandle navigationMoveTo;
    final MethodHandle navigationStop;
    final MethodHandle navigationSetCanFloat;
    final MethodHandle getBrain;
    final MethodHandle stopSleeping;
    /** {@code Mob.goalSelector} / {@code Mob.targetSelector} field getters (may be null). */
    final MethodHandle goalSelectorGetter;
    final MethodHandle targetSelectorGetter;
    /** {@code GoalSelector.removeAllGoals(Predicate)} and its set fallback. */
    final MethodHandle removeAllGoals;
    final MethodHandle availableGoalsGetter;
    /** Head yaw and pitch accessors, for the display rig's head tracking. */
    final MethodHandle getHeadYaw;
    final MethodHandle getXRot;

    private NmsEntityPlan(MethodHandle getHandle, MethodHandle getNavigation, MethodHandle navigationMoveTo,
                          MethodHandle navigationStop, MethodHandle navigationSetCanFloat,
                          MethodHandle getBrain, MethodHandle stopSleeping,
                          MethodHandle goalSelectorGetter, MethodHandle targetSelectorGetter,
                          MethodHandle removeAllGoals, MethodHandle availableGoalsGetter,
                          MethodHandle getHeadYaw, MethodHandle getXRot) {
        this.getHandle = getHandle;
        this.getNavigation = getNavigation;
        this.navigationMoveTo = navigationMoveTo;
        this.navigationStop = navigationStop;
        this.navigationSetCanFloat = navigationSetCanFloat;
        this.getBrain = getBrain;
        this.stopSleeping = stopSleeping;
        this.goalSelectorGetter = goalSelectorGetter;
        this.targetSelectorGetter = targetSelectorGetter;
        this.removeAllGoals = removeAllGoals;
        this.availableGoalsGetter = availableGoalsGetter;
        this.getHeadYaw = getHeadYaw;
        this.getXRot = getXRot;
    }

    /** The plan for a living entity's CraftBukkit class; never {@code null}. */
    static NmsEntityPlan forEntity(org.bukkit.entity.LivingEntity entity) {
        NmsEntityPlan plan = PLANS.computeIfAbsent(entity.getClass(), NmsEntityPlan::resolve);
        return plan == EMPTY ? null : plan;
    }

    /** Builds the reflection plan for one CraftBukkit entity class. */
    private static synchronized NmsEntityPlan resolve(Class<?> craftClass) {
        MethodHandle getHandle = findNoArg(craftClass, "getHandle");
        if (getHandle == null) {
            return EMPTY;
        }
        Class<?> entityClass = getHandle.type().returnType();

        MethodHandle getNavigation = findNoArg(entityClass, "getNavigation");
        if (getNavigation == null) {
            getNavigation = findNoArgByReturnTypeName(entityClass, "Navigation");
        }
        MethodHandle navigationMoveTo = null;
        MethodHandle navigationStop = null;
        MethodHandle navigationSetCanFloat = null;
        if (getNavigation != null) {
            Class<?> navigationClass = getNavigation.type().returnType();
            navigationMoveTo = findNavigationMoveTo(navigationClass);
            navigationStop = findNoArg(navigationClass, "stop");
            navigationSetCanFloat = findSingleBoolean(navigationClass, "setCanFloat");
        }

        MethodHandle getBrain = findNoArg(entityClass, "getBrain");
        MethodHandle stopSleeping = findNoArg(entityClass, "stopSleeping");

        MethodHandle goalSelectorGetter = findFieldGetter(entityClass, "goalSelector");
        MethodHandle targetSelectorGetter = findFieldGetter(entityClass, "targetSelector");
        MethodHandle removeAllGoals = null;
        MethodHandle availableGoalsGetter = null;
        Class<?> selectorClass = goalSelectorGetter != null
                ? goalSelectorGetter.type().returnType()
                : (targetSelectorGetter != null ? targetSelectorGetter.type().returnType() : null);
        if (selectorClass != null) {
            removeAllGoals = findRemoveAllGoals(selectorClass);
            availableGoalsGetter = findNoArgByReturnTypeName(selectorClass, "Set");
            if (availableGoalsGetter != null
                    && !Collection.class.isAssignableFrom(availableGoalsGetter.type().returnType())) {
                availableGoalsGetter = null;
            }
        }

        MethodHandle getHeadYaw = findNoArg(entityClass, "getYHeadRot");
        if (getHeadYaw == null || getHeadYaw.type().returnType() != float.class) {
            getHeadYaw = findFloatFieldGetter(entityClass, "yHeadRot");
        }
        MethodHandle getXRot = findNoArg(entityClass, "getXRot");
        if (getXRot == null || getXRot.type().returnType() != float.class) {
            getXRot = null;
        }

        return new NmsEntityPlan(getHandle, getNavigation, navigationMoveTo, navigationStop,
                navigationSetCanFloat, getBrain, stopSleeping, goalSelectorGetter, targetSelectorGetter,
                removeAllGoals, availableGoalsGetter, getHeadYaw, getXRot);
    }

    /**
     * Clears both vanilla AI selectors on an entity handle. AI itself stays
     * enabled (navigation ticks live in {@code Mob.aiStep}); only the goals are
     * removed, which is what "no vanilla behavior" means without freezing path
     * following.
     *
     * @return {@code true} when at least one selector was cleared
     */
    boolean clearGoals(Object entityHandle) {
        boolean cleared = false;
        cleared |= clearSelector(entityHandle, goalSelectorGetter);
        cleared |= clearSelector(entityHandle, targetSelectorGetter);
        return cleared;
    }

    private boolean clearSelector(Object entityHandle, MethodHandle getter) {
        if (getter == null) {
            return false;
        }
        try {
            Object selector = getter.invoke(entityHandle);
            if (selector == null) {
                return false;
            }
            if (removeAllGoals != null) {
                removeAllGoals.invoke(selector, ALWAYS_TRUE);
                return true;
            }
            if (availableGoalsGetter != null && COLLECTION_CLEAR != null) {
                Object goals = availableGoalsGetter.invoke(selector);
                if (goals instanceof Collection<?> collection) {
                    COLLECTION_CLEAR.invoke(collection);
                    return true;
                }
            }
        } catch (Throwable unavailable) {
            // A remapped server loses AI isolation, not the whole mob system.
        }
        return false;
    }

    /** The entity's head yaw, or {@code fallback} when the runtime exposes no accessor. */
    float headYaw(Object entityHandle, float fallback) {
        if (getHeadYaw == null) {
            return fallback;
        }
        try {
            return (float) getHeadYaw.invoke(entityHandle);
        } catch (Throwable unavailable) {
            return fallback;
        }
    }

    /** The entity's pitch, or {@code fallback} when the runtime exposes no accessor. */
    float pitch(Object entityHandle, float fallback) {
        if (getXRot == null) {
            return fallback;
        }
        try {
            return (float) getXRot.invoke(entityHandle);
        } catch (Throwable unavailable) {
            return fallback;
        }
    }


    // ------------------------------------------------------------------
    // Reflection helpers (mojang-name first, structural probe second)
    // ------------------------------------------------------------------

    /** Public non-static no-arg method by name, anywhere on the class chain. */
    private static MethodHandle findNoArg(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                        || !method.getName().equals(name)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** No-arg method whose return type's simple name contains the fragment. */
    private static MethodHandle findNoArgByReturnTypeName(Class<?> start, String returnTypeFragment) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getReturnType().getSimpleName().contains(returnTypeFragment)) {
                    try {
                        method.setAccessible(true);
                        return MethodHandles.lookup().unreflect(method);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** The navigation {@code moveTo(x, y, z, speed)} on the navigation chain. */
    private static MethodHandle findNavigationMoveTo(Class<?> navigationClass) {
        for (Class<?> c = navigationClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals("moveTo") || method.getParameterCount() != 4) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types[0] == double.class && types[1] == double.class
                        && types[2] == double.class && types[3] == double.class) {
                    try {
                        method.setAccessible(true);
                        return MethodHandles.lookup().unreflect(method);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static MethodHandle findSingleBoolean(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != boolean.class
                        || Modifier.isStatic(method.getModifiers()) || !method.getName().equals(name)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Getter for a non-static field by name, anywhere on the class chain. */
    private static MethodHandle findFieldGetter(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getName().equals(name)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return MethodHandles.lookup().unreflectGetter(field);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** {@code GoalSelector.removeAllGoals(Predicate)} with a one-parameter shape probe. */
    private static MethodHandle findRemoveAllGoals(Class<?> selectorClass) {
        for (Class<?> c = selectorClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || Modifier.isStatic(method.getModifiers())
                        || !method.getName().equals("removeAllGoals")) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Getter for a {@code float} field, used for runtimes that expose no yaw accessor. */
    private static MethodHandle findFloatFieldGetter(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != float.class
                        || !field.getName().equals(name)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return MethodHandles.lookup().unreflectGetter(field);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static MethodHandle clearHandle() {
        try {
            return MethodHandles.lookup().findVirtual(Collection.class, "clear",
                    java.lang.invoke.MethodType.methodType(void.class));
        } catch (Throwable unavailable) {
            return null;
        }
    }
}

