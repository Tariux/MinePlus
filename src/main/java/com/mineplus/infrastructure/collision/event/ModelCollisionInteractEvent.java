package com.mineplus.infrastructure.collision.event;

import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player right-clicks the interaction hitbox of a placed model.
 *
 * <p>Fired before the underlying {@code PlayerInteractEntityEvent} is allowed to
 * continue: cancelling this event cancels that one too, so no vanilla
 * interaction (armour stand pose, item frame rotation) reaches the technical
 * entity. An author who wants the model's own GUI opens it here.</p>
 *
 * @since 1.4.0
 */
public class ModelCollisionInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Entity clickedEntity;
    private final PlacedFurniture furniture;
    private final PlacedPart part;
    private final Location clicked;
    private boolean cancelled;

    public ModelCollisionInteractEvent(Player player, Entity clickedEntity,
            PlacedFurniture furniture, PlacedPart part, Location clicked) {
        this.player = player;
        this.clickedEntity = clickedEntity;
        this.furniture = furniture;
        this.part = part;
        this.clicked = clicked;
    }

    /** Player who clicked. */
    public Player getPlayer() {
        return player;
    }

    /** The interaction entity that was hit (a technical entity, not a model). */
    public Entity getClickedEntity() {
        return clickedEntity;
    }

    /** Model instance whose hitbox was clicked. */
    public PlacedFurniture getFurniture() {
        return furniture;
    }

    /** The interaction part that was hit; its cell is relative to the origin. */
    public PlacedPart getPart() {
        return part;
    }

    /** Location of the clicked hitbox, for a GUI anchored to it. */
    public Location getClickedLocation() {
        return clicked;
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
