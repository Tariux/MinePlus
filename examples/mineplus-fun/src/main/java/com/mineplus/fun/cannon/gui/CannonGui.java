package com.mineplus.fun.cannon.gui;

import com.mineplus.fun.cannon.CannonKeys;
import com.mineplus.fun.cannon.CannonMountManager;
import com.mineplus.fun.cannon.CannonTntStore;
import com.mineplus.infrastructure.core.gui.InfrastructureGui;
import com.mineplus.infrastructure.core.gui.InteractiveInfrastructureGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import java.util.List;
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
 * Cannon context menu. The centre slot accepts and holds one stack of
 * ammunition — TNT (ballistic shots) or fire charges (straight-flying
 * fireballs, fired first while any are loaded). At level 1 the menu carries
 * the upgrade button (anvil, consumes the next level's cost); from level 2 it
 * offers the gunner's seat (saddle button that mounts the player) and a short
 * manual explaining the aimed-fire mechanics.
 */
public final class CannonGui implements InfrastructureGui, InteractiveInfrastructureGui {

    private static final int SIZE = 9;
    private static final int AMMO_SLOT = 4;
    private static final int INFO_SLOT = 2;
    private static final int SADDLE_SLOT = 6;

    private final JavaPlugin plugin;
    private final MultiBlockRegistry registry;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final CannonMountManager mounts;

    public CannonGui(JavaPlugin plugin, MultiBlockRegistry registry, MultiBlockLifecycleManager lifecycleManager, CannonMountManager mounts) {
        this.plugin = plugin;
        this.registry = registry;
        this.lifecycleManager = lifecycleManager;
        this.mounts = mounts;
    }

    @Override
    public void open(Player player, MultiBlockInstance instance) {
        Inventory inventory = Bukkit.createInventory(player, SIZE, ChatColor.DARK_GRAY + "Cannon");
        fillLayout(inventory, instance);

        // Fire charges display first because they are fired first.
        int fireballs = CannonTntStore.loadFireballs(instance);
        if (fireballs > 0) {
            inventory.setItem(AMMO_SLOT, new ItemStack(Material.FIRE_CHARGE, Math.min(fireballs, 64)));
        } else {
            int tnt = CannonTntStore.load(instance);
            if (tnt > 0) {
                inventory.setItem(AMMO_SLOT, new ItemStack(Material.TNT, Math.min(tnt, 64)));
            }
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

        if (rawSlot != AMMO_SLOT) {
            event.setCancelled(true);
            if (rawSlot == INFO_SLOT && instance.level() < CannonKeys.LEVEL_AIMED) {
                upgrade(player, instance);
            } else if (rawSlot == SADDLE_SLOT && instance.level() >= CannonKeys.LEVEL_AIMED) {
                UUID targetInstance = instance.id();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    MultiBlockInstance live = registry.getInstance(targetInstance);
                    if (live == null) {
                        return;
                    }
                    player.closeInventory();
                    mounts.mount(player, live);
                });
            }
            return;
        }

        if (!isAmmoOrEmpty(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        ItemStack swapCandidate = swapCandidate(event);
        if (!isAmmoOrEmpty(swapCandidate)) {
            event.setCancelled(true);
            return;
        }

        scheduleStateCapture(player, instanceId, top);
    }

    @Override
    public void onDrag(Player player, UUID instanceId, InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && slot != AMMO_SLOT) {
                event.setCancelled(true);
                return;
            }
        }

        if (!isAmmoOrEmpty(event.getOldCursor())) {
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

    private boolean isAmmoOrEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getType() == Material.TNT || item.getType() == Material.FIRE_CHARGE;
    }

    private void fillLayout(Inventory inventory, MultiBlockInstance instance) {
        boolean aimed = instance.level() >= CannonKeys.LEVEL_AIMED;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(AMMO_SLOT, null);
        inventory.setItem(INFO_SLOT, aimed ? manual() : upgradeButton(instance));
        if (aimed) {
            inventory.setItem(SADDLE_SLOT, saddleButton());
        }
    }

    /** Upgrades through the Core lifecycle (consumes the next level's cost), then refreshes the menu. */
    private void upgrade(Player player, MultiBlockInstance instance) {
        UUID instanceId = instance.id();
        Bukkit.getScheduler().runTask(plugin, () -> {
            MultiBlockInstance live = registry.getInstance(instanceId);
            if (live == null) {
                return;
            }
            boolean upgraded = lifecycleManager.upgrade(instanceId, player);
            if (!upgraded) {
                player.sendMessage(ChatColor.RED + "Upgrade failed. Check materials and level cap.");
                return;
            }
            MultiBlockInstance refreshed = registry.getInstance(instanceId);
            if (refreshed != null) {
                open(player, refreshed);
            }
        });
    }

    private ItemStack upgradeButton(MultiBlockInstance instance) {
        MultiBlockType type = registry.getType(instance.typeId());
        if (type == null || type.level(instance.level() + 1) == null) {
            return named(Material.BARRIER, ChatColor.RED + "Max Level");
        }

        var next = type.level(instance.level() + 1);
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Upgrade to Cannon II");
        List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.GRAY + "Reforges the cannon into a bigger");
        lore.add(ChatColor.GRAY + "piece with a gunner's seat.");
        lore.add(ChatColor.GRAY + "Cost:");
        for (Map.Entry<String, Integer> entry : next.upgradeCost().entrySet()) {
            lore.add(ChatColor.YELLOW + "- " + entry.getValue() + "x " + readable(entry.getKey()));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String readable(String key) {
        String normalized = key;
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized.replace('_', ' ');
    }

    private ItemStack manual() {
        ItemStack item = named(Material.SPYGLASS, ChatColor.GOLD + "Gunner's Manual");
        ItemMeta meta = item.getItemMeta();
        meta.setLore(List.of(
                ChatColor.GRAY + "Load TNT or fire charges into the centre",
                ChatColor.GRAY + "slot; fire charges are fired first.",
                ChatColor.GRAY + "Take the gunner's seat here, or right-click",
                ChatColor.GRAY + "the cannon while holding a saddle.",
                ChatColor.GRAY + "Draw the Cannon Lanyard like a bow to fire;",
                ChatColor.GRAY + "longer draws hit harder.",
                ChatColor.DARK_GRAY + "Sneak to leave the seat."
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack saddleButton() {
        return named(Material.SADDLE, ChatColor.GOLD + "Take the Gunner's Seat");
    }

    private void scheduleStateCapture(Player player, UUID instanceId, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> capture(player, instanceId, inventory));
    }

    private void capture(Player player, UUID instanceId, Inventory inventory) {
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance == null) {
            return;
        }

        ItemStack ammo = inventory.getItem(AMMO_SLOT);
        if (ammo == null || ammo.getType().isAir()) {
            CannonTntStore.save(instance, 0);
            CannonTntStore.saveFireballs(instance, 0);
            return;
        }

        if (ammo.getType() == Material.TNT) {
            CannonTntStore.save(instance, ammo.getAmount());
            CannonTntStore.saveFireballs(instance, 0);
            return;
        }

        if (ammo.getType() == Material.FIRE_CHARGE) {
            CannonTntStore.saveFireballs(instance, ammo.getAmount());
            CannonTntStore.save(instance, 0);
            return;
        }

        // Defensive: the guarded interaction paths never let foreign items in,
        // but never destroy player items if one somehow appears anyway.
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(ammo);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        inventory.setItem(AMMO_SLOT, null);
        CannonTntStore.save(instance, 0);
        CannonTntStore.saveFireballs(instance, 0);
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }
}
