package com.mineplus.infrastructure.listener;

import com.mineplus.infrastructure.api.event.VirtualBlockInstanceRemoveEvent;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import com.mineplus.infrastructure.core.block.BlockItemFactory;
import com.mineplus.infrastructure.core.block.BlockPlacementService;
import com.mineplus.infrastructure.core.block.BlockRegistry;
import com.mineplus.infrastructure.core.block.BlockStore;
import com.mineplus.infrastructure.core.block.PlacedBlock;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Item-in-hand placement, drop on break, and chunk-load restore for blocks. */
public final class BlockPlacementListener implements Listener {

    private final BlockRegistry registry;
    private final BlockItemFactory items;
    private final BlockPlacementService placement;
    private final BlockStore store;

    public BlockPlacementListener(
            BlockRegistry registry,
            BlockItemFactory items,
            BlockPlacementService placement,
            BlockStore store
    ) {
        this.registry = registry;
        this.items = items;
        this.placement = placement;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        String id = items.readId(item);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        BlockDefinition definition = registry.get(id);
        if (definition == null) {
            event.getPlayer().sendMessage(ChatColor.RED
                    + "This block item points at an unknown definition: " + id);
            return;
        }
        BlockPlacementService.Outcome outcome = placement.place(event.getPlayer(), definition);
        if (!outcome.placed()) {
            event.getPlayer().sendMessage(ChatColor.RED + outcome.message());
            return;
        }
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE && item != null) {
            item.setAmount(item.getAmount() - 1);
        }
        event.getPlayer().playSound(event.getPlayer().getLocation(),
                Sound.BLOCK_WOOD_PLACE, 0.8f, 1.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoved(VirtualBlockInstanceRemoveEvent event) {
        PlacedBlock record = store.get(event.getInstanceId());
        if (record == null) {
            return;
        }
        if (event.getReason() == VirtualBlockInstanceRemoveEvent.Reason.BLOCK_BREAK) {
            Location origin = event.getOrigin();
            if (origin.getWorld() != null) {
                ItemStack drop = placement.dropFor(record);
                if (drop != null) {
                    origin.getWorld().dropItemNaturally(
                            origin.clone().add(0.5, 0.5, 0.5), drop);
                }
            }
        }
        placement.forget(event.getInstanceId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        placement.restoreInChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
