package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.api.event.VirtualBlockClearAreaEvent;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;
import java.util.UUID;

/**
 * Fired before Mineplus clears blocks to make room for a virtual model's
 * spawn area. Cancelling keeps the placement from destroying any blocks;
 * listeners can also trim the {@link #getCells() cell list}.
 *
 * @since 1.3.0
 */
public class VirtualBlockClearAreaEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID modelId;
    private final Location origin;
    private final List<Location> cells;
    private boolean cancelled;

    public VirtualBlockClearAreaEvent(UUID modelId, Location origin, List<Location> cells) {
        super(!org.bukkit.Bukkit.isPrimaryThread()); // async only when actually off-thread
        this.modelId = modelId;
        this.origin = origin;
        this.cells = cells;
    }

    /** Id of the virtual model about to spawn. @since 1.3.0 */
    public UUID getModelId() {
        return modelId;
    }

    /** Origin the model spawns at. @since 1.3.0 */
    public Location getOrigin() {
        return origin.clone();
    }

    /** Mutable list of candidate cells; remove entries to spare them. @since 1.3.0 */
    public List<Location> getCells() {
        return cells;
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
