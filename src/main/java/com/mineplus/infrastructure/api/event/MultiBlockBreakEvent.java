package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a multiblock instance is removed. Cancelling keeps the
 * instance intact. {@code destroy} distinguishes a physical break (model
 * dropped, structure torn down) from an administrative delete.
 *
 * @since 1.3.0
 */
public class MultiBlockBreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MultiBlockInstance instance;
    private final MultiBlockType type;
    private final Player actor;
    private final boolean destroy;
    private boolean cancelled;

    public MultiBlockBreakEvent(MultiBlockInstance instance, MultiBlockType type, Player actor, boolean destroy) {
        this.instance = instance;
        this.type = type;
        this.actor = actor;
        this.destroy = destroy;
    }

    /** Instance being removed. @since 1.3.0 */
    public MultiBlockInstance getInstance() {
        return instance;
    }

    /** Type definition of the instance. @since 1.3.0 */
    public MultiBlockType getType() {
        return type;
    }

    /** Player breaking the structure, or {@code null} for system removals. @since 1.3.0 */
    public Player getActor() {
        return actor;
    }

    /** {@code true} for physical breaks, {@code false} for admin deletes. @since 1.3.0 */
    public boolean isDestroy() {
        return destroy;
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
