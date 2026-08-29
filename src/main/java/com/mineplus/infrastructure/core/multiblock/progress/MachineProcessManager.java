package com.mineplus.infrastructure.core.multiblock.progress;

import com.mineplus.infrastructure.core.events.HookBus;
import com.mineplus.infrastructure.core.multiblock.EntityStatus;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.EntityStateMachine;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEventType;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.core.recipes.RecipeManager;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Engine for timed crafting processes on multiblock instances.
 *
 * <p>Makes the previously unused {@code craftTimeTicks} recipe field and the previously
 * unused {@code speedMultiplier} level field meaningful: a process started via
 * {@link #start(UUID, String)} counts down over multiple lifecycle ticks, advances
 * faster on higher machine levels, and fires {@code PROCESS_START} /
 * {@code PROCESS_COMPLETE} lifecycle events plus the corresponding
 * {@code onProcessStart} / {@code onProcessComplete} hook callbacks when it finishes.
 *
 * <p>Persistence is free: process state lives in the instance's {@code stateData}
 * map, which the persistence layer already snapshots, so processes survive restarts
 * and resume automatically once the machine's chunk is loaded again. Processes on
 * machines in unloaded chunks are simply not advanced (vanilla-parity behavior),
 * but their state is retained.
 *
 * <p>Thread-safety: all methods must be called on the main server thread, matching
 * the lifecycle tick that drives {@link #advanceAll(int)}.
 */
public final class MachineProcessManager {

    private final MultiBlockRegistry registry;
    private final RecipeManager recipeManager;
    private final HookBus hookBus;

    public MachineProcessManager(MultiBlockRegistry registry, RecipeManager recipeManager, HookBus hookBus) {
        this.registry = registry;
        this.recipeManager = recipeManager;
        this.hookBus = hookBus;
    }

    /**
     * Starts a timed process for a recipe on an instance.
     *
     * <p>Validation performed: the instance exists and is {@code ACTIVE}, the recipe
     * exists, belongs to the instance's machine type, and is unlocked at the
     * instance's current level. Only one process can run per instance at a time;
     * starting a new one while another runs fails.
     *
     * <p>The engine never consumes inputs or produces outputs (zero-content policy);
     * the calling feature decides what "starting" means for its own inventory. The
     * engine only tracks time and notifies via lifecycle events and hooks.
     *
     * @param instanceId the instance to run the process on
     * @param recipeId   the recipe to run
     * @return {@code true} if the process was started
     */
    public boolean start(UUID instanceId, String recipeId) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return false;
        }
        EntityStatus status = instance.status() == null ? EntityStatus.CREATED : instance.status();
        if (status != EntityStatus.ACTIVE) {
            return false;
        }
        if (MachineProcess.decodeFrom(instance.mutableStateData()) != null) {
            return false;
        }
        MachineRecipe recipe = recipeManager.get(recipeId);
        if (recipe == null || !recipe.machineTypeId().equalsIgnoreCase(type.id())) {
            return false;
        }
        if (recipe.minLevel() > instance.level()) {
            return false;
        }

        MachineProcess full = new MachineProcess(recipe.id(), type.id(),
                Math.max(1, recipe.craftTimeTicks()), Math.max(1, recipe.craftTimeTicks()));
        full.encodeInto(instance.mutableStateData());

        hookBus.publish(new MultiBlockLifecycleEvent(
                MultiBlockLifecycleEventType.PROCESS_START, type, instance, null, null));
        safeHook(instance, "onProcessStart", () -> type.hook().onProcessStart(instance, recipe));
        DebugLogger.info("MachineProcessManager: Started process '" + recipe.id()
                + "' on instance " + instanceId + " (" + full.totalTicks() + " ticks base).");
        return true;
    }

    /**
     * Cancels the running process on an instance, if any. No events are fired;
     * cancellation is treated as a silent reset of the process state.
     *
     * @param instanceId the instance whose process should be cancelled
     * @return {@code true} if a process was running and has been cleared
     */
    public boolean cancel(UUID instanceId) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return false;
        }
        boolean had = MachineProcess.decodeFrom(instance.mutableStateData()) != null;
        MachineProcess.clearFrom(instance.mutableStateData());
        return had;
    }

    /**
     * Returns the running process of an instance.
     *
     * @param instanceId the instance to inspect
     * @return the current process, or {@code null} if none is running
     */
    public MachineProcess get(UUID instanceId) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return null;
        }
        return MachineProcess.decodeFrom(instance.mutableStateData());
    }

    /**
     * Advances all running processes by one lifecycle tick interval. Called from the
     * lifecycle manager's repeating tick task.
     *
     * <p>Each call consumes {@code intervalTicks * speedMultiplier} ticks of unscaled
     * recipe time, where the speed multiplier comes from the instance's current level —
     * so a mid-process upgrade immediately speeds up the running process. Processes on
     * instances in unloaded chunks are skipped but retained. When the remaining time
     * reaches zero, the process completes: state is cleared, a {@code PROCESS_COMPLETE}
     * event is published, and the type's {@code onProcessComplete} hook fires.
     *
     * @param intervalTicks the number of ticks between lifecycle ticks (always 20 today)
     * @return the number of processes that completed during this advance
     */
    public int advanceAll(int intervalTicks) {
        return advanceAll(intervalTicks, null);
    }

    /**
     * Advances all running processes by one lifecycle tick interval, reporting
     * every instance whose process state changed to the optional
     * {@code dirtySink} so the caller can stage incremental persistence for
     * exactly those instances.
     *
     * @param intervalTicks the number of ticks between lifecycle ticks (always 20 today)
     * @param dirtySink     optional consumer invoked with each instance id whose
     *                      encoded process state was read or written this pass
     * @return the number of processes that completed during this advance
     */
    public int advanceAll(int intervalTicks, java.util.function.Consumer<UUID> dirtySink) {
        int completed = 0;
        List<MachineProcessCompletion> finished = new ArrayList<>();
        for (MultiBlockInstance instance : registry.getInstances()) {
            if (instance.status() != EntityStatus.ACTIVE) {
                continue;
            }
            if (!EntityStateMachine.validateChunkLoaded(instance)) {
                continue;
            }
            MachineProcess process = MachineProcess.decodeFrom(instance.mutableStateData());
            if (process == null) {
                continue;
            }

            double speed = currentSpeed(instance);
            int advance = speed <= 0 ? 0 : (int) Math.max(1, Math.round(intervalTicks * speed));
            int remaining = process.remainingTicks() - advance;
            MachineRecipe recipe = recipeManager.get(process.recipeId());
            MultiBlockType type = registry.getType(instance.typeId());

            if (recipe == null || type == null || !recipe.machineTypeId().equalsIgnoreCase(type.id())) {
                // Recipe/type vanished (e.g. config reload) — drop the orphaned process.
                MachineProcess.clearFrom(instance.mutableStateData());
                if (dirtySink != null) {
                    dirtySink.accept(instance.id());
                }
                DebugLogger.info("MachineProcessManager: Dropped orphaned process '"
                        + process.recipeId() + "' on instance " + instance.id() + " (recipe/type missing).");
                continue;
            }

            if (remaining <= 0) {
                MachineProcess.clearFrom(instance.mutableStateData());
                finished.add(new MachineProcessCompletion(instance, type, recipe));
            } else {
                MachineProcess updated = new MachineProcess(
                        process.recipeId(), process.machineTypeId(), process.totalTicks(), remaining);
                updated.encodeInto(instance.mutableStateData());
            }
            if (dirtySink != null) {
                dirtySink.accept(instance.id());
            }
        }

        for (MachineProcessCompletion completion : finished) {
            hookBus.publish(new MultiBlockLifecycleEvent(
                    MultiBlockLifecycleEventType.PROCESS_COMPLETE,
                    completion.type(), completion.instance(), null, null));
            safeHook(completion.instance(), "onProcessComplete",
                    () -> completion.type().hook().onProcessComplete(completion.instance(), completion.recipe()));
            completed++;
        }
        return completed;
    }

    /**
     * Runs a direct per-type hook dispatch with exception isolation: a throwing
     * module hook is logged and skipped instead of aborting the advancement loop.
     */
    private void safeHook(MultiBlockInstance instance, String phase, Runnable dispatch) {
        try {
            dispatch.run();
        } catch (Throwable throwable) {
            DebugLogger.severe("MachineProcessManager: Hook '" + phase + "' of type '" + instance.typeId()
                    + "' (instance " + instance.id() + ") threw; isolating and continuing. "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    /** Resolves the current level's speed multiplier for an instance, clamped to [0, ...]. */
    private double currentSpeed(MultiBlockInstance instance) {
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null) {
            return 1.0;
        }
        MultiBlockLevel level = type.level(instance.level());
        if (level == null) {
            return 1.0;
        }
        return Math.max(0.0, level.speedMultiplier());
    }

    private record MachineProcessCompletion(
            MultiBlockInstance instance,
            MultiBlockType type,
            MachineRecipe recipe
    ) {
    }
}
