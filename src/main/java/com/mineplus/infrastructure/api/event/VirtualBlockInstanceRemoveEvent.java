package com.mineplus.infrastructure.api.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a virtual model instance is removed (lattice blocks lifted,
 * display entities gone), whatever the reason: a player breaking a lattice
 * cell, an area clear, chunk restore, or an explicit remove call.
 *
 * <p>This is the hook external rendering backends listen for: a plugin that
 * mounted its own pack display on an instance removes that visual here, so
 * nothing can outlive the instance. For {@code FRAME}/{@code
 * INTERACTION_ONLY} anchored models the interaction entity is removed before
 * this fires, and the event is the only notification they need.
 *
 * @since 1.3.0
 */
public class VirtualBlockInstanceRemoveEvent extends Event {

    /** Why the instance was removed. */
    public enum Reason {
        /** A player or piston broke one of the lattice cells. */
        BLOCK_BREAK,
        /** {@code /mineplus clear} or {@link com.mineplus.infrastructure.virtual.VirtualBlockManager#removeAllModels()}. */
        CLEAR,
        /** Explicit {@code removeModel(instanceId)} call. */
        EXPLICIT
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID instanceId;
    private final String modelKey;
    private final Location origin;
    private final Reason reason;

    public VirtualBlockInstanceRemoveEvent(UUID instanceId, String modelKey, Location origin, Reason reason) {
        super(true); // async=true: safe to fire from any thread; Bukkit syncs dispatch
        this.instanceId = instanceId;
        this.modelKey = modelKey;
        this.origin = origin;
        this.reason = reason;
    }

    /** Id of the removed virtual block instance. @since 1.3.0 */
    public UUID getInstanceId() {
        return instanceId;
    }

    /** Model key the instance was spawned from. @since 1.3.0 */
    public String getModelKey() {
        return modelKey;
    }

    /** Spawn origin the instance was anchored at. @since 1.3.0 */
    public Location getOrigin() {
        return origin.clone();
    }

    /** Why the instance was removed. @since 1.3.0 */
    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
