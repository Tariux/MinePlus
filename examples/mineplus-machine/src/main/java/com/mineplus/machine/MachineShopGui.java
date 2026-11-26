package com.mineplus.machine;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /machine shop} chest GUI: one entry per machine type; clicking an
 * entry hands the player that machine's placeable item and closes the view.
 */
final class MachineShopGui implements InventoryHolder {

    private final MineplusMachinePlugin feature;
    private final Inventory inventory;
    private final List<String> typeIds = new ArrayList<>();

    MachineShopGui(MineplusMachinePlugin feature) {
        this.feature = feature;
        int rows = Math.max(1, (MineplusMachinePlugin.MACHINE_TYPE_IDS.length + 8) / 9);
        this.inventory = Bukkit.createInventory(this, rows * 9,
                ChatColor.DARK_AQUA + "Machine Shop");
        rebuild();
    }

    private void rebuild() {
        inventory.clear();
        typeIds.clear();
        for (String typeId : MineplusMachinePlugin.MACHINE_TYPE_IDS) {
            ItemStack item = feature.itemListener() == null
                    ? null
                    : feature.itemListener().itemFor(typeId);
            if (item == null) {
                continue;
            }
            typeIds.add(typeId);
            inventory.setItem(typeIds.size() - 1, item);
        }
    }

    void open(Player player) {
        player.openInventory(inventory);
    }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= typeIds.size()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String typeId = typeIds.get(slot);
        ItemStack item = feature.itemListener().itemFor(typeId);
        if (item == null) {
            return;
        }
        var leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.get(0));
        }
        player.sendMessage(ChatColor.GREEN + "Received " + typeId
                + ChatColor.GRAY + " — right-click a block face to place it.");
        for (HumanEntity viewer : List.copyOf(inventory.getViewers())) {
            viewer.closeInventory();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
