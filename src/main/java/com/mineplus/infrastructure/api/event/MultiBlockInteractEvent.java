package com.mineplus.infrastructure.api.event;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player right-clicks a multiblock instance, before module hooks
 * and the GUI open. Cancelling makes the click fall through as a normal
 * block interaction.
 *
 * @since 1.3.0
 */
public class MultiBlockInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MultiBlockInstance instance;
    private final MultiBlockType type;
    private final Player player;
    private boolean cancelled;

    public MultiBlockInteractEvent(MultiBlockInstance instance, MultiBlockType type, Player player) {
        this.instance = instance;
        this.type = type;
        this.player = player;
    }

    /** Instance that was clicked. @since 1.3.0 */
    public MultiBlockInstance getInstance() {
        return instance;
    }

    /** Type definition of the instance. @since 1.3.0 */
    public MultiBlockType getType() {
        return type;
    }

    /** Player who clicked. @since 1.3.0 */
    public Player getPlayer() {
        return player;
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
