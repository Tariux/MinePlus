package com.mineplus.gun.event;

import com.mineplus.gun.weapon.WeaponDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired on the main thread immediately before a reload begins.
 *
 * <p>Cancelling it leaves the magazine exactly as it was — no reserve is
 * consumed and no reload pose, sound or HUD transition starts. An automatic
 * reload (the self-reload that follows the last round) is cancellable too, so a
 * plugin can gate reloading without distinguishing who asked for it.</p>
 */
public final class WeaponReloadEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack item;
    private final WeaponDefinition weapon;
    private final int ticks;
    private final boolean automatic;
    private boolean cancelled;

    public WeaponReloadEvent(Player player, ItemStack item, WeaponDefinition weapon,
                             int ticks, boolean automatic) {
        this.player = player;
        this.item = item;
        this.weapon = weapon;
        this.ticks = ticks;
        this.automatic = automatic;
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

    /** How long the reload will take, in ticks. */
    public int ticks() {
        return ticks;
    }

    /** {@code true} when the gun asked for the reload itself (empty magazine). */
    public boolean automatic() {
        return automatic;
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

    /** Calls the event; {@code true} when the reload may proceed. */
    public static boolean allow(Player player, ItemStack item, WeaponDefinition weapon,
                                int ticks, boolean automatic) {
        WeaponReloadEvent event = new WeaponReloadEvent(player, item, weapon, ticks, automatic);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
