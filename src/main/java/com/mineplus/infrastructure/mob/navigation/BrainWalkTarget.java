package com.mineplus.infrastructure.mob.navigation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * The vanilla Brain walk-target channel: sets the {@code WALK_TARGET} memory
 * on a Brain-driven mob (villagers), which is exactly how the mob's own AI
 * moves itself â€” hazard-aware pathing, speed control and arrival distance all
 * stay vanilla, and no other vanilla behavior fights the walk.
 *
 * <p>The NMS classes resolve once per JVM through mojang-mapped names (every
 * 1.20.5+ runtime); any failure disables the channel and the caller falls
 * back to plain path navigation.
 */
final class BrainWalkTarget {

    /** One-time channel resolution state (JVM-global; NMS classes are global). */
    private static volatile boolean resolved;
    private static MethodHandle blockPosCtor;
    private static MethodHandle walkTargetCtor;
    private static Object walkTargetMemoryType;
    private static MethodHandle brainSetMemory;
    private static MethodHandle brainEraseMemory;
    private static boolean brainSetMemoryOptional;

    private BrainWalkTarget() {
    }

    /**
     * Points {@code mob}'s Brain at {@code target}.
     *
     * @param plan  the mob's resolved NMS plan (must carry {@code getBrain})
     * @param speed the movement-speed modifier (1.0 = normal walk)
     * @return {@code true} when the walk target was set
     */
    static boolean apply(NmsEntityPlan plan, Mob mob, Location target, double speed) {
        if (plan == null || plan.getBrain == null || plan.getHandle == null) {
            return false;
        }
        try {
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return false;
            }
            Object brain = plan.getBrain.invoke(handle);
            if (brain == null || !resolveChannel(plan.getBrain.type().returnType())) {
                return false;
            }
            Object pos = blockPosCtor.invoke(target.getBlockX(), target.getBlockY(), target.getBlockZ());
            Object walkTarget = walkTargetCtor.invoke(pos, (float) speed, 1);
            if (brainSetMemoryOptional) {
                brainSetMemory.invoke(brain, walkTargetMemoryType, Optional.of(walkTarget));
            } else {
                brainSetMemory.invoke(brain, walkTargetMemoryType, walkTarget);
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Erases {@code mob}'s {@code WALK_TARGET} memory. Without this a villager
     * keeps walking to a target the caller has abandoned, so a "stopped" mob
     * would ignore every later command.
     */
    static boolean clear(NmsEntityPlan plan, Mob mob) {
        if (plan == null || plan.getHandle == null || plan.getBrain == null) {
            return false;
        }
        try {
            if (!resolveChannel(plan.getBrain.type().returnType()) || brainEraseMemory == null) {
                return false;
            }
            Object handle = plan.getHandle.invoke(mob);
            if (handle == null) {
                return false;
            }
            Object brain = plan.getBrain.invoke(handle);
            if (brain == null) {
                return false;
            }
            brainEraseMemory.invoke(brain, walkTargetMemoryType);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** One-time resolution of the Brain memory classes (mojang-mapped names). */
    private static synchronized boolean resolveChannel(Class<?> brainClass) {
        if (resolved) {
            return channelUsable();
        }
        resolved = true;
        try {
            Class<?> memoryModuleTypeClass = Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType");
            walkTargetMemoryType = memoryModuleTypeClass.getField("WALK_TARGET").get(null);

            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            blockPosCtor = constructor(blockPosClass, int.class, int.class, int.class);

            Class<?> walkTargetClass = Class.forName("net.minecraft.world.entity.ai.memory.WalkTarget");
            walkTargetCtor = constructor(walkTargetClass, blockPosClass, float.class, int.class);

            brainSetMemory = findSetMemory(brainClass, false);
            if (brainSetMemory == null) {
                // Only the Optional overload exists: wrap the value per call.
                brainSetMemory = findSetMemory(brainClass, true);
                brainSetMemoryOptional = brainSetMemory != null;
            }
            brainEraseMemory = findEraseMemory(brainClass,
                    memoryModuleTypeClass);
        } catch (Throwable failure) {
            return false;
        }
        return channelUsable();
    }

    private static boolean channelUsable() {
        return blockPosCtor != null && walkTargetCtor != null
                && walkTargetMemoryType != null && brainSetMemory != null;
    }

    private static MethodHandle constructor(Class<?> owner, Class<?>... wanted) {
        for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
            if (parameterTypesMatch(ctor.getParameterTypes(), wanted)) {
                try {
                    ctor.setAccessible(true);
                    return MethodHandles.lookup().unreflectConstructor(ctor);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean parameterTypesMatch(Class<?>[] actual, Class<?>... expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** The raw-value {@code setMemory} overload when present, else the Optional one. */
    private static MethodHandle findSetMemory(Class<?> brainClass, boolean optionalOverload) {
        for (Method method : brainClass.getDeclaredMethods()) {
            if (!method.getName().equals("setMemory") || method.getParameterCount() != 2
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            boolean optional = method.getParameterTypes()[1] == Optional.class;
            if (optionalOverload != optional) {
                continue;
            }
            try {
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** {@code Brain#eraseMemory(MemoryModuleType)} — the only way to end a Brain walk. */
    private static MethodHandle findEraseMemory(Class<?> brainClass, Class<?> memoryModuleTypeClass) {
        for (Method method : brainClass.getDeclaredMethods()) {
            if (!method.getName().equals("eraseMemory") || method.getParameterCount() != 1
                    || Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes()[0] != memoryModuleTypeClass) {
                continue;
            }
            try {
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}

