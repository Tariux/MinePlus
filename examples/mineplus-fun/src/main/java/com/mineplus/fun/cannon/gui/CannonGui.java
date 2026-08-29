package com.mineplus.fun.cannon.gui;

import com.mineplus.fun.cannon.CannonKeys;
import com.mineplus.fun.cannon.CannonMountManager;
import com.mineplus.fun.cannon.CannonTntStore;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.gui.AbstractMachineGui;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cannon context menu built on the Core's {@link AbstractMachineGui}. The
 * centre slot accepts and holds one stack of ammunition — TNT (ballistic
 * shots) or fire charges (straight-flying fireballs, fired first while any
 * are loaded). At level 1 the menu carries the upgrade button (anvil,
 * consumes the next level's cost); from level 2 it offers the gunner's seat
 * (saddle button that mounts the player) and a short manual explaining the
 * aimed-fire mechanics.
 */
public final class CannonGui extends AbstractMachineGui {

    private static final int SIZE = 9;
    private static final int AMMO_SLOT = 4;
    private static final int INFO_SLOT = 2;
    private static final int SADDLE_SLOT = 6;

    private final PluginContext context;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final CannonMountManager mounts;

    public CannonGui(JavaPlugin plugin, PluginContext context, MultiBlockLifecycleManager lifecycleManager, CannonMountManager mounts) {
        super(plugin, context.infrastructureEngine().registry(), SIZE);
        this.context = context;
        this.lifecycleManager = lifecycleManager;
        this.mounts = mounts;
    }

    @Override
    protected String title(MultiBlockInstance instance) {
        return ChatColor.DARK_GRAY + "Cannon";
    }

    @Override
    protected void layout(Inventory inventory, MultiBlockInstance instance) {
        boolean aimed = instance.level() >= CannonKeys.LEVEL_AIMED;
        fill(inventory, fillerPane());
        inventory.setItem(AMMO_SLOT, null);
        inventory.setItem(INFO_SLOT, aimed ? manual() : upgradeButton(instance));
        if (aimed) {
            inventory.setItem(SADDLE_SLOT, saddleButton());
        }

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
    }

    @Override
    protected Set<Integer> containerSlots() {
        return Set.of(AMMO_SLOT);
    }

    @Override
    protected boolean accepts(int slot, ItemStack item) {
        return item == null || item.getType().isAir()
                || item.getType() == Material.TNT
                || item.getType() == Material.FIRE_CHARGE;
    }

    @Override
    protected void onButtonClick(Player player, MultiBlockInstance instance, int slot, InventoryClickEvent event) {
        if (slot == INFO_SLOT && instance.level() < CannonKeys.LEVEL_AIMED) {
            upgrade(player, instance);
        } else if (slot == SADDLE_SLOT && instance.level() >= CannonKeys.LEVEL_AIMED) {
            UUID targetInstance = instance.id();
            Bukkit.getScheduler().runTask(plugin(), () -> {
                MultiBlockInstance live = instance(targetInstance);
                if (live == null) {
                    return;
                }
                player.closeInventory();
                mounts.mount(player, live);
            });
        }
    }

    @Override
    protected void capture(Player player, MultiBlockInstance instance, Inventory inventory) {
        ItemStack ammo = inventory.getItem(AMMO_SLOT);
        if (ammo == null || ammo.getType().isAir()) {
            CannonTntStore.save(instance, 0);
            CannonTntStore.saveFireballs(instance, 0);
            context.infrastructureApi().stagePersist(instance.id());
            return;
        }

        if (ammo.getType() == Material.TNT) {
            CannonTntStore.save(instance, ammo.getAmount());
            CannonTntStore.saveFireballs(instance, 0);
            context.infrastructureApi().stagePersist(instance.id());
            return;
        }

        if (ammo.getType() == Material.FIRE_CHARGE) {
            CannonTntStore.saveFireballs(instance, ammo.getAmount());
            CannonTntStore.save(instance, 0);
            context.infrastructureApi().stagePersist(instance.id());
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

    /** Upgrades through the Core lifecycle (consumes the next level's cost), then refreshes the menu. */
    private void upgrade(Player player, MultiBlockInstance instance) {
        UUID instanceId = instance.id();
        Bukkit.getScheduler().runTask(plugin(), () -> {
            MultiBlockInstance live = instance(instanceId);
            if (live == null) {
                return;
            }
            boolean upgraded = lifecycleManager.upgrade(instanceId, player);
            if (!upgraded) {
                player.sendMessage(ChatColor.RED + "Upgrade failed. Check materials and level cap.");
                return;
            }
            MultiBlockInstance refreshed = instance(instanceId);
            if (refreshed != null) {
                open(player, refreshed);
            }
        });
    }

    private ItemStack upgradeButton(MultiBlockInstance instance) {
        MultiBlockType type = type(instance);
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
}
