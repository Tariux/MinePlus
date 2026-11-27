package com.mineplus.infrastructure.listener;

import com.mineplus.infrastructure.core.block.BlockActionRunner;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import com.mineplus.infrastructure.core.block.BlockItemFactory;
import com.mineplus.infrastructure.core.block.BlockRegistry;
import com.mineplus.infrastructure.core.block.BlockStore;
import com.mineplus.infrastructure.core.block.PlacedBlock;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Routes right-click on a placed block to its definition, never to vanilla. */
public final class BlockInteractionListener implements Listener {

    private final VirtualBlockManager manager;
    private final BlockRegistry registry;
    private final BlockStore store;
    private final BlockItemFactory items;

    public BlockInteractionListener(
            VirtualBlockManager manager,
            BlockRegistry registry,
            BlockStore store,
            BlockItemFactory items
    ) {
        this.manager = manager;
        this.registry = registry;
        this.store = store;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (items.readId(event.getItem()) != null) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        UUID instanceId = manager.getInstanceIdAt(event.getClickedBlock().getLocation());
        if (instanceId == null) {
            return;
        }
        event.setCancelled(true);
        PlacedBlock record = store.get(instanceId);
        if (record == null) {
            return;
        }
        BlockDefinition definition = registry.get(record.definitionId());
        if (definition == null) {
            return;
        }
        BlockActionRunner.run(event.getPlayer(), definition, definition.events().onInteract());
    }
}
