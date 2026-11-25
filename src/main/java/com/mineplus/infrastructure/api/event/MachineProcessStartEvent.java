package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a timed machine process starts on an instance. Cancelling
 * rejects the start; the caller's {@code start(...)} returns {@code false}.
 *
 * @since 1.3.0
 */
public class MachineProcessStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MultiBlockInstance instance;
    private final String recipeId;
    private boolean cancelled;

    public MachineProcessStartEvent(MultiBlockInstance instance, String recipeId) {
        this.instance = instance;
        this.recipeId = recipeId;
    }

    /** Machine the process would run on. @since 1.3.0 */
    public MultiBlockInstance getInstance() {
        return instance;
    }

    /** Recipe the process would run. @since 1.3.0 */
    public String getRecipeId() {
        return recipeId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
