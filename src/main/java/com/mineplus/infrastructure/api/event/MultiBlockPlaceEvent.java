package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a multiblock instance transitions to PLACED. Cancelling aborts
 * the placement; the instance stays in CREATED state.
 *
 * @since 1.3.0
 */
public class MultiBlockPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MultiBlockInstance instance;
    private final MultiBlockType type;
    private final Player actor;
    private boolean cancelled;

    public MultiBlockPlaceEvent(MultiBlockInstance instance, MultiBlockType type, Player actor) {
        this.instance = instance;
        this.type = type;
        this.actor = actor;
    }

    /** Instance about to be placed. @since 1.3.0 */
    public MultiBlockInstance getInstance() {
        return instance;
    }

    /** Type definition of the instance. @since 1.3.0 */
    public MultiBlockType getType() {
        return type;
    }

    /** Player placing the structure, or {@code null} for system placements. @since 1.3.0 */
    public Player getActor() {
        return actor;
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
