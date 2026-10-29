package com.mineplus.gun.event;

import com.mineplus.gun.weapon.WeaponDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired on the main thread immediately before a weapon spends a round.
 *
 * <p>Cancelling it stops the shot completely: no ammunition is consumed, no
 * round enters the simulation, no pose or sound plays. It is the seam for
 * safe-zone rules, gamemode gates, cooldown plugins and tests that need to
 * dry-fire a gun without touching it.</p>
 *
 * <p>The {@link #item()} is a snapshot of the stack in hand at the moment the
 * trigger resolved; mutating it does not change anything the runtime will do
 * with the real stack.</p>
 */
public final class WeaponFireEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack item;
    private final WeaponDefinition weapon;
    private boolean cancelled;

    public WeaponFireEvent(Player player, ItemStack item, WeaponDefinition weapon) {
        this.player = player;
        this.item = item;
        this.weapon = weapon;
    }

    public Player player() {
        return player;
    }

    public ItemStack item() {
        return item;
    }

    public WeaponDefinition weapon() {
        return weapon;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * Calls the event without an explicit plugin reference. Kept here so the
     * runtime cannot forget which handler list it belongs to.
     */
    public static boolean allow(Player player, ItemStack item, WeaponDefinition weapon) {
        WeaponFireEvent event = new WeaponFireEvent(player, item, weapon);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
