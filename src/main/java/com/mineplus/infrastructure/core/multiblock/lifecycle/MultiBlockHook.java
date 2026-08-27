package com.mineplus.infrastructure.core.multiblock.lifecycle;

import com.mineplus.infrastructure.core.events.MultiBlockSignal;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import org.bukkit.entity.Player;

public interface MultiBlockHook {

    default void onCreate(MultiBlockInstance instance, Player actor) {
    }

    default void onCraft(MultiBlockInstance instance, Player actor) {
    }

    default void onPlace(MultiBlockInstance instance, Player actor) {
    }

    default void onInteract(MultiBlockInstance instance, Player actor) {
    }

    default void onTick(MultiBlockInstance instance) {
    }

    default void onUpgrade(MultiBlockInstance instance, int previousLevel, int nextLevel, Player actor) {
    }

    default void onBreak(MultiBlockInstance instance, Player actor) {
    }

    default void onRemove(MultiBlockInstance instance, Player actor) {
    }

    default void onModelReload(MultiBlockInstance instance) {
    }

    default void onSignal(MultiBlockInstance instance, MultiBlockSignal signal) {
    }

    /**
     * Called when a timed crafting process is started on an instance via the
     * {@code MachineProcessManager}. The engine tracks only time; consuming inputs
     * is the feature's responsibility.
     *
     * @param instance the machine the process was started on
     * @param recipe   the recipe being processed
     */
    default void onProcessStart(MultiBlockInstance instance, MachineRecipe recipe) {
    }

    /**
     * Called when a timed crafting process finishes on an instance. Producing
     * outputs is the feature's responsibility; the engine guarantees this fires
     * exactly once per started process, unless the process is cancelled, the
     * machine is removed, or its recipe disappears in a config reload.
     *
     * @param instance the machine the process completed on
     * @param recipe   the recipe that finished
     */
    default void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
    }
}
