package com.mineplus.infrastructure.collision.event;

import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the world thread before the technical parts of a model collision are
 * placed. Cancelling leaves the model without collision.
 *
 * @since 1.4.0
 */
public class ModelCollisionBuiltEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlacedFurniture furniture;
    private boolean cancelled;

    public ModelCollisionBuiltEvent(PlacedFurniture furniture) {
        this.furniture = furniture;
    }

    /** Model instance about to be given collision. */
    public PlacedFurniture getFurniture() {
        return furniture;
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
