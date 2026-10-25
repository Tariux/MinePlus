package com.mineplus.gun.input;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Abstraction over press/release/aim/switch signals so the fire-flow runtime
 * never branches on the concrete input source. The Bukkit adapter is always
 * available; a packet-level adapter can drop in later without touching weapon
 * logic.
 */
public interface InputAdapter {

    /** Runtime callbacks. The held stack is passed raw; the runtime resolves the weapon. */
    interface Listener {
        /**
         * True when {@code stack} is one of this module's weapons. Used by the
         * adapter to cancel vanilla behaviour <em>before</em> invoking the
         * runtime, so an unexpected runtime error can never let a vanilla
         * action through.
         */
        boolean isWeapon(ItemStack stack);

        /**
         * Called on right-click press. Return true to cancel the vanilla action
         * (prevent block placement, item use, etc.). Return false to allow it.
         */
        boolean onPress(Player player, ItemStack stack);

        void onRelease(Player player);

        /**
         * Called on left-click swing. Unused by the Bukkit adapter for weapons
         * (left-click is inert so no arm-punch animation plays); kept on the
         * seam for a future packet-driven input source.
         */
        void onSwing(Player player, ItemStack stack);

        void onReload(Player player, ItemStack stack);

        /**
         * Called when a player attacks an entity (left-click). Return true to
         * cancel vanilla melee damage (the runtime will handle melee via onSwing).
         */
        boolean onAttack(Player player, ItemStack stack);
    }

    /** Registers this adapter's listeners against {@code plugin}. */
    void register(org.bukkit.plugin.java.JavaPlugin plugin, Listener listener);

    /** Removes listeners (safe on a never-registered adapter). */
    void unregister();

    /** Short label for {@code /mpgun stats}. */
    String name();
}
