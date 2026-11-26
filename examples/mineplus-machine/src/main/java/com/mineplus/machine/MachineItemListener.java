package com.mineplus.machine;

import com.mineplus.infrastructure.virtual.PlacementOptions;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Locale;

/**
 * Machine items written by {@code /machine give <type>}: an item carrying the
 * {@code machine-type} PDC tag. Right-clicking a block places that machine
 * type anchored to the clicked face's neighbour block — the survival-friendly
 * path into {@code /machine place}. The raw item can never be placed as a
 * vanilla block.
 */
final class MachineItemListener implements Listener {

    /** PDC key carrying the machine type id on a give item. */
    static final String MACHINE_TAG = "machine-type";

    private final MineplusMachinePlugin feature;
    private final NamespacedKey typeKey;

    MachineItemListener(MineplusMachinePlugin feature) {
        this.feature = feature;
        this.typeKey = new NamespacedKey(feature.plugin(), MACHINE_TAG);
    }

    /** Builds the placeable machine item for a type id (null when unknown). */
    ItemStack itemFor(String typeId) {
        var type = feature.context().infrastructureApi() == null ? null : feature.typeById(typeId);
        if (type == null) {
            return null;
        }
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + type.displayName());
        meta.setLore(List.of(
                ChatColor.GRAY + "Right-click a block to place this machine.",
                ChatColor.DARK_GRAY + "machine: " + typeId));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, typeId);
        item.setItemMeta(meta);
        return item;
    }

    /** Reads the machine type id from an item, or null. */
    String machineType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) {
            return;
        }
        String typeId = machineType(event.getItem());
        if (typeId == null) {
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        if (!placeMachine(event.getPlayer(), target, typeId)) {
            return;
        }
        event.getItem().setAmount(event.getItem().getAmount() - 1);
        event.getPlayer().playSound(target.getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
    }

    /** Machine items never place as their vanilla backing block. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (machineType(event.getItemInHand()) != null) {
            event.setCancelled(true);
        }
    }

    /** Places one machine of {@code typeId} anchored at {@code target}; returns false when refused. */
    private boolean placeMachine(Player player, Block target, String typeId) {
        var api = feature.context().infrastructureApi();
        Location location = target.getLocation().add(0.5, 0.5, 0.5);
        if (feature.typeById(typeId) == null) {
            return false;
        }
        var created = api.createMultiBlock(typeId, location,
                player.getUniqueId(), player.getUniqueId(), new Quaternionf());
        if (created == null || !api.placeMultiBlock(created.id(), player)) {
            player.sendMessage(ChatColor.RED + "Cannot place " + typeId + " there.");
            return false;
        }
        player.sendMessage(ChatColor.GREEN + typeId + " placed.");
        return true;
    }

    static String normalize(String typeId) {
        return typeId.toLowerCase(Locale.ROOT);
    }
}
