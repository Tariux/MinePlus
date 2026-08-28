package com.mineplus.fun.cannon.gui;

import com.mineplus.fun.cannon.CannonTntStore;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.gui.InteractiveInfrastructureGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cannon ammunition menu: a single slot that accepts and holds one stack of TNT.
 * Each shot consumes one TNT from that stack (see {@code CannonFireHook}).
 */
public final class CannonGui implements InfrastructureGui, InteractiveInfrastructureGui {

    private static final int SIZE = 9;
    private static final int TNT_SLOT = 4;

    private final JavaPlugin plugin;
    private final MultiBlockRegistry registry;

    public CannonGui(JavaPlugin plugin, MultiBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void open(Player player, MultiBlockInstance instance) {
        Inventory inventory = Bukkit.createInventory(player, SIZE, ChatColor.DARK_GRAY + "Cannon");
        fillLayout(inventory);

        int loaded = CannonTntStore.load(instance);
        if (loaded > 0) {
            inventory.setItem(TNT_SLOT, new ItemStack(Material.TNT, Math.min(loaded, 64)));
        }

        player.openInventory(inventory);
    }

    @Override
    public void onClick(Player player, UUID instanceId, InventoryClickEvent event) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            event.setCancelled(true);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        int rawSlot = event.getRawSlot();
        if (rawSlot >= top.getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        if (rawSlot != TNT_SLOT) {
            event.setCancelled(true);
            return;
        }

        if (!isTntOrEmpty(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        ItemStack swapCandidate = swapCandidate(event);
        if (!isTntOrEmpty(swapCandidate)) {
            event.setCancelled(true);
            return;
        }

        scheduleStateCapture(player, instanceId, top);
    }

    @Override
    public void onDrag(Player player, UUID instanceId, InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && slot != TNT_SLOT) {
                event.setCancelled(true);
                return;
            }
        }

        if (!isTntOrEmpty(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }

        scheduleStateCapture(player, instanceId, event.getView().getTopInventory());
    }

    @Override
    public void onClose(Player player, UUID instanceId, InventoryCloseEvent event) {
        capture(player, instanceId, event.getView().getTopInventory());
    }

    /**
     * The item a hotbar-number (or off-hand, reported as button 40) swap click would
     * move into the TNT slot.
     */
    private ItemStack swapCandidate(InventoryClickEvent event) {
        if (event.getHotbarButton() >= 0) {
            return event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
        }
        return null;
    }

    private boolean isTntOrEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getType() == Material.TNT;
    }

    private void fillLayout(Inventory inventory) {
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(TNT_SLOT, null);
    }

    private void scheduleStateCapture(Player player, UUID instanceId, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> capture(player, instanceId, inventory));
    }

    private void capture(Player player, UUID instanceId, Inventory inventory) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return;
        }

        ItemStack tnt = inventory.getItem(TNT_SLOT);
        if (tnt == null || tnt.getType().isAir()) {
            CannonTntStore.save(instance, 0);
            return;
        }

        if (tnt.getType() != Material.TNT) {
            // Defensive: the guarded interaction paths never let foreign items in,
            // but never destroy player items if one somehow appears anyway.
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(tnt);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            inventory.setItem(TNT_SLOT, null);
            CannonTntStore.save(instance, 0);
            return;
        }

        CannonTntStore.save(instance, tnt.getAmount());
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }
}
