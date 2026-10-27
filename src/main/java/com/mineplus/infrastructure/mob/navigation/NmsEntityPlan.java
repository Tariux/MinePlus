package com.mineplus.infrastructure.mob.navigation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.LivingEntity;

/**
 * The resolved NMS reflection plan for one CraftBukkit entity class: the
 * {@code getHandle()} bridge plus the vanilla navigation, Brain and sleep
 * handles the reflective backends operate through.
 *
 * <p>Plans are built lazily per CraftBukkit class and cached forever;
 * {@link #EMPTY} marks an unresolvable class so the probe never re-runs.
 * Lookups prefer mojang-mapped names (every 1.20.5+ runtime) and fall back to
 * a structural probe (return-type shape) for exotic remaps.
 */
final class NmsEntityPlan {

    /** Sentinel for "this CraftBukkit class cannot be bridged". */
    static final NmsEntityPlan EMPTY = new NmsEntityPlan(null, null, null, null, null, null, null);

    private static final Map<Class<?>, NmsEntityPlan> PLANS = new ConcurrentHashMap<>();

    final MethodHandle getHandle;
    final MethodHandle getNavigation;
    final MethodHandle navigationMoveTo;
    final MethodHandle navigationStop;
    final MethodHandle navigationSetCanFloat;
    final MethodHandle getBrain;
    final MethodHandle stopSleeping;

    private NmsEntityPlan(MethodHandle getHandle, MethodHandle getNavigation, MethodHandle navigationMoveTo,
                          MethodHandle navigationStop, MethodHandle navigationSetCanFloat,
                          MethodHandle getBrain, MethodHandle stopSleeping) {
        this.getHandle = getHandle;
        this.getNavigation = getNavigation;
        this.navigationMoveTo = navigationMoveTo;
        this.navigationStop = navigationStop;
        this.navigationSetCanFloat = navigationSetCanFloat;
        this.getBrain = getBrain;
        this.stopSleeping = stopSleeping;
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
        return new NmsEntityPlan(getHandle, getNavigation, navigationMoveTo, navigationStop,
                navigationSetCanFloat, getBrain, stopSleeping);
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
}

