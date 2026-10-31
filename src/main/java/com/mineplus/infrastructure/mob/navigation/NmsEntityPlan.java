package com.mineplus.infrastructure.mob.navigation;

import com.mineplus.util.DebugLogger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * The resolved NMS reflection plan for one CraftBukkit entity class: the
 * {@code getHandle()} bridge plus the vanilla navigation, look control, Brain
 * and goal-selector handles the reflective backends operate through.
 *
 * <p>Plans are built lazily per CraftBukkit class and cached forever;
 * {@link #EMPTY} marks an unresolvable class so the probe never re-runs.</p>
 *
 * <p><b>Mapping tolerance.</b> A runtime can expose either mojang names
 * ({@code getNavigation}, {@code moveTo}, {@code stop}, {@code goalSelector},
 * {@code getBrain}) or obfuscated Spigot names ({@code N()}, {@code a(double,
 * double, double, double)}, {@code bW}, {@code dT()}). Named lookups therefore
 * only ever act as a preferred fast path: every handle is resolved by shape
 * (parameter list, return type, field type) so one jar serves both mappings.
 * Nothing here assumes a field name, an enum key type, or that a class has a
 * Brain.</p>
 *
 * <p><b>Isolation contract.</b> Clearing vanilla AI must not remove movement:
 * {@code setAI(false)} also stops the navigation tick, so the plan only ever
 * empties goals, the goal selector, and the Brain's activity containers.
 * Memories, sensors and the schedule are left registered; the mob stays a
 * well-formed neighbour while running no vanilla behavior.</p>
 */
final class NmsEntityPlan {

    /** Sentinel for "this CraftBukkit class cannot be bridged". */
    static final NmsEntityPlan EMPTY = new NmsEntityPlan();

    private static final Map<Class<?>, NmsEntityPlan> PLANS = new ConcurrentHashMap<>();
    private static final MethodHandle COLLECTION_CLEAR = clearHandle();
    private static final Predicate<Object> ALWAYS_TRUE = ignored -> true;

    final MethodHandle getHandle;
    final MethodHandle getNavigation;
    final MethodHandle navigationMoveTo;
    /** Named {@code stop()} (mojang) — a no-arg void method. */
    final MethodHandle navigationStop;
    /** Setter for the navigation's path field (Spigot {@code n()}); written with {@code null}. */
    final MethodHandle navigationPathSetter;
    final MethodHandle navigationSetCanFloat;
    /** {@code ControllerLook}/{@code LookControl} accessor and its 3-double/2-float look call. */
    final MethodHandle getLookControl;
    final MethodHandle lookAt;
    final MethodHandle getBrain;
    final MethodHandle stopSleeping;
    /** The entity's goal and target selectors (either may be null). */
    final MethodHandle goalSelectorGetter;
    final MethodHandle targetSelectorGetter;
    /** {@code GoalSelector.removeAllGoals(Predicate)} and its available-goals set. */
    final MethodHandle removeAllGoals;
    final MethodHandle availableGoalsGetter;
    /** Head yaw and pitch accessors, for the display rig's head tracking. */
    final MethodHandle getHeadYaw;
    final MethodHandle getXRot;

    /** {@code Brain.clearBehaviors()} when the runtime names it. */
    final MethodHandle clearBrainBehaviors;
    /**
     * Non-static {@code Map}/{@code Collection} fields of the Brain, each with
     * its declared generic type. Only the activity-keyed subset is ever
     * emptied (see {@link #clearBrainStructural(Object)}).
     */
    private final ContainerField[] brainContainers;
    /**
     * Activity containers resolved from the generic signatures. Empty when the
     * runtime erased them; the live-content fallback then classifies one Brain
     * on first use.
     */
    private volatile MethodHandle[] brainActivityGetters;
    private volatile boolean liveClassificationAttempted;

    private NmsEntityPlan() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, new ContainerField[0]);
    }

    private NmsEntityPlan(MethodHandle getHandle, MethodHandle getNavigation, MethodHandle navigationMoveTo,
                          MethodHandle navigationStop, MethodHandle navigationPathSetter,
                          MethodHandle navigationSetCanFloat, MethodHandle getLookControl, MethodHandle lookAt,
                          MethodHandle getBrain, MethodHandle stopSleeping,
                          MethodHandle goalSelectorGetter, MethodHandle targetSelectorGetter,
                          MethodHandle removeAllGoals, MethodHandle availableGoalsGetter,
                          MethodHandle getHeadYaw, MethodHandle getXRot,
                          MethodHandle clearBrainBehaviors, ContainerField[] brainContainers) {
        this.getHandle = getHandle;
        this.getNavigation = getNavigation;
        this.navigationMoveTo = navigationMoveTo;
        this.navigationStop = navigationStop;
        this.navigationPathSetter = navigationPathSetter;
        this.navigationSetCanFloat = navigationSetCanFloat;
        this.getLookControl = getLookControl;
        this.lookAt = lookAt;
        this.getBrain = getBrain;
        this.stopSleeping = stopSleeping;
        this.goalSelectorGetter = goalSelectorGetter;
        this.targetSelectorGetter = targetSelectorGetter;
        this.removeAllGoals = removeAllGoals;
        this.availableGoalsGetter = availableGoalsGetter;
        this.getHeadYaw = getHeadYaw;
        this.getXRot = getXRot;
        this.clearBrainBehaviors = clearBrainBehaviors;
        this.brainContainers = brainContainers == null ? new ContainerField[0] : brainContainers;
        this.brainActivityGetters = activityContainers(this.brainContainers);
    }

    /**
     * The plan for a living entity's CraftBukkit class; never {@code null}.
     *
     * <p>The plan is resolved against the <em>live handle's</em> class, not the
     * declared return type of {@code getHandle()}. CraftBukkit classes are free
     * to declare the bridge as {@code Entity getHandle()} (CraftVillager does),
     * and a plan built from that base class finds no navigation, look control,
     * Brain or goal selectors at all — a villager custom mob then keeps its
     * vanilla AI and cannot be steered.</p>
     */
    static NmsEntityPlan forEntity(org.bukkit.entity.LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        NmsEntityPlan plan = PLANS.get(entity.getClass());
        if (plan != null) {
            return plan == EMPTY ? null : plan;
        }
        plan = resolveLive(entity);
        return plan == null || plan == EMPTY ? null : plan;
    }

    /**
     * Resolves (and caches) the plan for one CraftBukkit class using one live
     * entity's real NMS handle. A genuine base-class bridge is cached as
     * {@link #EMPTY}; a transient failure is not, so a later entity retries.
     */
    private static synchronized NmsEntityPlan resolveLive(org.bukkit.entity.LivingEntity entity) {
        Class<?> craftClass = entity.getClass();
        NmsEntityPlan cached = PLANS.get(craftClass);
        if (cached != null) {
            return cached;
        }
        MethodHandle getHandle = findNoArg(craftClass, "getHandle");
        if (getHandle == null) {
            PLANS.put(craftClass, EMPTY);
            return EMPTY;
        }
        Object handle;
        try {
            handle = getHandle.invoke(entity);
        } catch (Throwable unavailable) {
            return null;
        }
        if (handle == null) {
            return null;
        }
        NmsEntityPlan plan = resolve(craftClass, getHandle, handle.getClass());
        PLANS.put(craftClass, plan);
        return plan;
    }

    /** Builds the reflection plan for one CraftBukkit class and its NMS handle class. */
    private static NmsEntityPlan resolve(Class<?> craftClass, MethodHandle getHandle, Class<?> entityClass) {

        MethodHandle getNavigation = findNoArg(entityClass, "getNavigation");
        if (getNavigation == null) {
            getNavigation = findNoArgByReturnTypeName(entityClass, "Navigation");
        }
        MethodHandle navigationMoveTo = null;
        MethodHandle navigationStop = null;
        MethodHandle navigationPathSetter = null;
        MethodHandle navigationSetCanFloat = null;
        if (getNavigation != null) {
            Class<?> navigationClass = getNavigation.type().returnType();
            navigationMoveTo = findNavigationMoveTo(navigationClass);
            navigationStop = findNoArg(navigationClass, "stop");
            if (navigationStop != null && navigationStop.type().returnType() != void.class) {
                navigationStop = null;
            }
            navigationPathSetter = findPathFieldSetter(navigationClass);
            navigationSetCanFloat = findSingleBoolean(navigationClass, "setCanFloat");
        }

        MethodHandle getLookControl = findNoArg(entityClass, "getLookControl");
        if (getLookControl == null) {
            getLookControl = findNoArgByReturnTypeName(entityClass, "ControllerLook");
        }
        MethodHandle lookAt = getLookControl == null ? null
                : findLookAt(getLookControl.type().returnType());

        MethodHandle getBrain = findNoArg(entityClass, "getBrain");
        if (getBrain == null) {
            getBrain = findNoArgByReturnTypeName(entityClass, "BehaviorController");
        }
        if (getBrain == null) {
            getBrain = findNoArgByReturnTypeName(entityClass, "Brain");
        }
        MethodHandle stopSleeping = findNoArg(entityClass, "stopSleeping");
        MethodHandle clearBrainBehaviors = null;
        ContainerField[] brainContainers = new ContainerField[0];
        if (getBrain != null) {
            Class<?> brainClass = getBrain.type().returnType();
            clearBrainBehaviors = findNoArg(brainClass, "clearBehaviors");
            brainContainers = containerFields(brainClass);
        }

        List<MethodHandle> selectors = selectorFields(entityClass);
        MethodHandle goalSelectorGetter = selectors.size() > 0 ? selectors.get(0) : null;
        MethodHandle targetSelectorGetter = selectors.size() > 1 ? selectors.get(1) : null;
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
                navigationPathSetter, navigationSetCanFloat, getLookControl, lookAt, getBrain,
                stopSleeping, goalSelectorGetter, targetSelectorGetter, removeAllGoals,
                availableGoalsGetter, getHeadYaw, getXRot, clearBrainBehaviors, brainContainers);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /**
     * Issues one vanilla path request. The look control is turned toward the
     * destination in the same call: with the vanilla goals cleared nothing else
     * rotates the entity, so without it a mob walks its path facing whatever
     * direction it happened to spawn in.
     */
    boolean moveTo(Object entityHandle, Object navigation, Location target, double speed) {
        boolean requested = false;
        if (navigationMoveTo != null) {
            try {
                requested = Boolean.TRUE.equals(navigationMoveTo.invoke(navigation,
                        target.getX(), target.getY(), target.getZ(), speed));
            } catch (Throwable unavailable) {
                requested = false;
            }
        }
        faceTarget(entityHandle, target.getX(), target.getY() + 1.0, target.getZ(), 20.0f, 20.0f);
        return requested;
    }

    /** Drops the current path ({@code stop()} or the path field set to null). */
    boolean stopNavigation(Object navigation) {
        if (navigation == null) {
            return false;
        }
        if (navigationStop != null) {
            try {
                navigationStop.invoke(navigation);
                return true;
            } catch (Throwable unavailable) {
                // Fall through to the field channel.
            }
        }
        if (navigationPathSetter != null) {
            try {
                navigationPathSetter.invoke(navigation, (Object) null);
                return true;
            } catch (Throwable unavailable) {
                return false;
            }
        }
        return false;
    }

    /** True when {@code canFloat} was configured (once per mob). */
    boolean setCanFloat(Object navigation, boolean value) {
        if (navigationSetCanFloat == null || navigation == null) {
            return false;
        }
        try {
            navigationSetCanFloat.invoke(navigation, value);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Turns the entity's head — and through the vanilla body-yaw smoothing, its
     * body — toward a world point. Used both by the navigator and by content
     * that wants a mob to look at something without walking.
     */
    void faceTarget(Object entityHandle, double x, double y, double z, float yawSpeed, float pitchSpeed) {
        if (lookAt == null || getLookControl == null || entityHandle == null) {
            return;
        }
        try {
            Object look = getLookControl.invoke(entityHandle);
            if (look != null) {
                lookAt.invoke(look, x, y, z, yawSpeed, pitchSpeed);
            }
        } catch (Throwable unavailable) {
            // A runtime without a usable look control keeps vanilla head motion.
        }
    }

    // ------------------------------------------------------------------
    // AI isolation
    // ------------------------------------------------------------------

    /**
     * Clears both vanilla AI selectors on an entity handle. AI itself stays
     * enabled (navigation ticks live in the mob's own step); only the goals are
     * removed.
     *
     * @return {@code true} when at least one selector was cleared
     */
    boolean clearGoals(Object entityHandle) {
        boolean cleared = false;
        cleared |= clearSelector(entityHandle, goalSelectorGetter);
        cleared |= clearSelector(entityHandle, targetSelectorGetter);
        return cleared;
    }

    /**
     * Empties the Brain's behavior registrations on a Brain-driven entity
     * (villagers, piglins, …). Called in addition to goal clearing, because a
     * Brain-driven mob's activity containers are what actually schedule walks
     * and work; goal selectors alone leave them running.
     *
     * @return {@code true} when the Brain was reachable and cleared
     */
    boolean clearBrain(Object entityHandle) {
        if (getBrain == null) {
            return false;
        }
        try {
            Object brain = getBrain.invoke(entityHandle);
            if (brain == null) {
                return false;
            }
            if (clearBrainBehaviors != null) {
                clearBrainBehaviors.invoke(brain);
                return true;
            }
        } catch (Throwable unavailable) {
            // A remapped server loses the named clearer, not the whole Brain.
        }
        return clearBrainStructural(entityHandle);
    }

    /**
     * Empties the Brain's <em>activity</em> containers: the per-activity
     * behavior lists (keyed by run priority, then activity) and the
     * active/core-activity sets. No vanilla behavior can then start.
     *
     * <p>It deliberately touches nothing else. The Brain's memory
     * <em>registration</em> map and sensor map are left intact — emptying the
     * memory registry makes neighbouring vanilla AI throw
     * {@code "Unregistered memory fetched"} (an iron golem asking villagers for
     * a golem, on Paper 1.21.1) — and the schedule is harmless without
     * behaviors. Sensors and memories keep updating data, so the mob is inert
     * but still a well-formed villager to everything around it.</p>
     *
     * <p>Selection is by declared generic type ({@code Activity} appears in
     * every activity-keyed container on both mappings), with a live-content
     * fallback for runtimes that erased generics.</p>
     */
    boolean clearBrainStructural(Object entityHandle) {
        if (getBrain == null || brainContainers.length == 0) {
            return false;
        }
        Object brain;
        try {
            brain = getBrain.invoke(entityHandle);
        } catch (Throwable unavailable) {
            return false;
        }
        if (brain == null) {
            return false;
        }
        MethodHandle[] activities = brainActivityGetters;
        if (activities.length == 0 && !liveClassificationAttempted) {
            liveClassificationAttempted = true;
            activities = classifyLive(brain);
            brainActivityGetters = activities;
            if (activities.length == 0) {
                DebugLogger.warning("[MobNavigation] No Brain activity container was identifiable on "
                        + brain.getClass().getName() + "; its vanilla behaviors stay enabled.");
            }
        }
        boolean cleared = false;
        for (MethodHandle getter : activities) {
            try {
                Object container = getter.invoke(brain);
                if (container instanceof Collection<?> collection) {
                    collection.clear();
                    cleared = true;
                } else if (container instanceof Map<?, ?> map) {
                    map.clear();
                    cleared = true;
                }
            } catch (Throwable unreachable) {
                // One inaccessible field must not abandon the rest of the Brain.
            }
        }
        return cleared;
    }

    /** The activity-keyed containers implied by a Brain's field generics. */
    private static MethodHandle[] activityContainers(ContainerField[] containers) {
        List<MethodHandle> activity = new ArrayList<>();
        for (ContainerField container : containers) {
            if (container.genericType().contains("Activity")) {
                activity.add(container.getter());
            }
        }
        return activity.toArray(new MethodHandle[0]);
    }

    /**
     * Last-resort classification against a live Brain: any container whose
     * current keys/elements are all constants of one enum class (the Brain's
     * {@code Activity}). Memory and sensor registries are keyed by non-enum
     * types, so they can never qualify.
     */
    private MethodHandle[] classifyLive(Object brain) {
        Class<?> activityClass = null;
        for (ContainerField field : brainContainers) {
            Object container;
            try {
                container = field.getter().invoke(brain);
            } catch (Throwable unreachable) {
                continue;
            }
            activityClass = enumKeyClass(container);
            if (activityClass != null) {
                break;
            }
        }
        if (activityClass == null) {
            return new MethodHandle[0];
        }
        List<MethodHandle> keep = new ArrayList<>();
        for (ContainerField field : brainContainers) {
            Object container;
            try {
                container = field.getter().invoke(brain);
            } catch (Throwable unreachable) {
                continue;
            }
            if (container != null && allActivity(container, activityClass)) {
                keep.add(field.getter());
            }
        }
        return keep.toArray(new MethodHandle[0]);
    }

    /** The enum class one container keys its activities by, or {@code null}. */
    private static Class<?> enumKeyClass(Object container) {
        Iterable<?> keys = keysOf(container);
        if (keys == null) {
            return null;
        }
        Class<?> found = null;
        for (Object key : keys) {
            if (key == null || !key.getClass().isEnum()) {
                return null;
            }
            if (found == null) {
                found = key.getClass();
            } else if (found != key.getClass()) {
                return null;
            }
        }
        return found;
    }

    /** True when the container is non-empty and made entirely of {@code activityClass} keys. */
    private static boolean allActivity(Object container, Class<?> activityClass) {
        Iterable<?> keys = keysOf(container);
        if (keys == null) {
            return false;
        }
        boolean any = false;
        for (Object key : keys) {
            if (key == null || !activityClass.isInstance(key)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static Iterable<?> keysOf(Object container) {
        return switch (container) {
            case Map<?, ?> map -> map.keySet();
            case Collection<?> collection -> collection;
            default -> null;
        };
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

    // ------------------------------------------------------------------
    // Head tracking
    // ------------------------------------------------------------------

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

    /** A Brain container field: its getter and its declared generic type. */
    private record ContainerField(MethodHandle getter, String genericType) {
    }

    // ------------------------------------------------------------------
    // Reflection helpers (shape first, name second)
    // ------------------------------------------------------------------

    /**
     * Both goal selectors of an entity class. The vanilla fields are typed
     * {@code GoalSelector} on mojang mappings and {@code PathfinderGoalSelector}
     * on Spigot; both simple names contain {@code GoalSelector}, and the
     * declaration order is goal-then-target on both.
     */
    private static List<MethodHandle> selectorFields(Class<?> entityClass) {
        List<MethodHandle> named = new ArrayList<>(2);
        MethodHandle goal = findFieldGetter(entityClass, "goalSelector");
        MethodHandle target = findFieldGetter(entityClass, "targetSelector");
        if (goal != null) {
            named.add(goal);
        }
        if (target != null) {
            named.add(target);
        }
        if (named.size() == 2) {
            return named;
        }
        List<MethodHandle> structural = new ArrayList<>(2);
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !field.getType().getSimpleName().contains("GoalSelector")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    structural.add(MethodHandles.lookup().unreflectGetter(field));
                } catch (Throwable ignored) {
                    // Not readable; the next field still applies.
                }
                if (structural.size() == 2) {
                    return structural;
                }
            }
        }
        return structural.isEmpty() ? named : structural;
    }

    /** {@code Map}/{@code Collection} fields of a Brain, with their generic type names. */
    private static ContainerField[] containerFields(Class<?> brainClass) {
        List<ContainerField> found = new ArrayList<>();
        for (Class<?> c = brainClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (!Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    found.add(new ContainerField(MethodHandles.lookup().unreflectGetter(field),
                            field.getGenericType().getTypeName()));
                } catch (Throwable ignored) {
                    // Not readable on this runtime; the next field still applies.
                }
            }
        }
        return found.toArray(new ContainerField[0]);
    }

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
                    // Keep looking: a bridge method may shadow the real one.
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
                        // Keep looking.
                    }
                }
            }
        }
        return null;
    }

    /** The navigation's 4-double path request, by name or by pure shape. */
    private static MethodHandle findNavigationMoveTo(Class<?> navigationClass) {
        for (Class<?> c = navigationClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 4 || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types[0] != double.class || types[1] != double.class
                        || types[2] != double.class || types[3] != double.class) {
                    continue;
                }
                if (method.getReturnType() != boolean.class && method.getReturnType() != void.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    // Keep looking.
                }
            }
        }
        return null;
    }

    /** Setter for the navigation's path field (the field that {@code stop()} nulls). */
    private static MethodHandle findPathFieldSetter(Class<?> navigationClass) {
        for (Class<?> c = navigationClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                if (!field.getType().getSimpleName().contains("Path")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return MethodHandles.lookup().unreflectSetter(field);
                } catch (Throwable ignored) {
                    // Keep looking.
                }
            }
        }
        return null;
    }

    /** {@code ControllerLook#a(double,double,double,float,float)} by shape. */
    private static MethodHandle findLookAt(Class<?> lookClass) {
        for (Class<?> c = lookClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 5 || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types[0] == double.class && types[1] == double.class && types[2] == double.class
                        && types[3] == float.class && types[4] == float.class) {
                    try {
                        method.setAccessible(true);
                        return MethodHandles.lookup().unreflect(method);
                    } catch (Throwable ignored) {
                        // Keep looking.
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
                    // Keep looking.
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
                    // Keep looking.
                }
            }
        }
        return null;
    }

    /**
     * {@code GoalSelector.removeAllGoals(Predicate)}: named on mojang mappings,
     * {@code a(Predicate)} (one of several overloads) on Spigot. The predicate
     * parameter is what identifies it.
     */
    private static MethodHandle findRemoveAllGoals(Class<?> selectorClass) {
        MethodHandle named = findOneParamVoid(selectorClass, "removeAllGoals");
        if (named != null) {
            return named;
        }
        for (Class<?> c = selectorClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) {
                    continue;
                }
                if (!Predicate.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    // Keep looking.
                }
            }
        }
        return null;
    }

    private static MethodHandle findOneParamVoid(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || !method.getName().equals(name)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method);
                } catch (Throwable ignored) {
                    // Keep looking.
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
                    // Keep looking.
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
