package com.mineplus.infrastructure.collision.event;

import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the world thread after a model's technical blocks and entities have
 * been removed. Informational; the removal already happened.
 *
 * @since 1.4.0
 */
public class ModelCollisionRemovedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlacedFurniture furniture;

    public ModelCollisionRemovedEvent(PlacedFurniture furniture) {
        this.furniture = furniture;
    }

    /** Model instance whose collision was removed. */
    public PlacedFurniture getFurniture() {
        return furniture;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
