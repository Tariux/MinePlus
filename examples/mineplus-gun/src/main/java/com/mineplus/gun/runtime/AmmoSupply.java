package com.mineplus.gun.runtime;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The ammunition economy bridge: reload rounds are drawn from the player's
 * inventory one nugget at a time (gold nuggets by default, config-driven).
 * The inventory stays the single source of truth — nothing is cached, so
 * looting, trading or crafting nuggets immediately affects the next reload.
 */
public final class AmmoSupply {

    private AmmoSupply() {
    }

    /** Total nuggets of the ammo material anywhere in the player's inventory. */
    public static int count(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Removes up to {@code amount} nuggets of the ammo material, lowest slots
     * first. Returns the number actually removed (stacks shrink cleanly, empty
     * stacks become air).
     */
    public static int take(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != material) {
                continue;
            }
            int used = Math.min(item.getAmount(), remaining);
            remaining -= used;
            if (used >= item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - used);
            }
        }
        return amount - remaining;
    }

    /** Adds nuggets to the player's inventory, overflowing to a ground drop. */
    public static void give(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, Math.max(1, material.getMaxStackSize()));
            var leftover = player.getInventory().addItem(new ItemStack(material, stackSize));
            for (ItemStack overflow : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            remaining -= stackSize;
        }
    }
}
