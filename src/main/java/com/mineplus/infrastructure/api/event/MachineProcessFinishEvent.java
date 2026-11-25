package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a timed machine process completes, before the module's
 * {@code onProcessComplete} hook runs. Informational (the output is already
 * produced); listen to {@link MachineProcessStartEvent} to prevent runs.
 *
 * @since 1.3.0
 */
public class MachineProcessFinishEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MultiBlockInstance instance;
    private final String recipeId;

    public MachineProcessFinishEvent(MultiBlockInstance instance, String recipeId) {
        this.instance = instance;
        this.recipeId = recipeId;
    }

    /** Machine that finished the process. @since 1.3.0 */
    public MultiBlockInstance getInstance() {
        return instance;
    }

    /** Recipe that was completed. @since 1.3.0 */
    public String getRecipeId() {
        return recipeId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
