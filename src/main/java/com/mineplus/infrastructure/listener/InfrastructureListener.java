package com.mineplus.infrastructure.listener;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class InfrastructureListener implements Listener {

    private final MultiBlockLifecycleManager lifecycleManager;
    private final VirtualBlockManager virtualBlockManager;

    public InfrastructureListener(
            MultiBlockLifecycleManager lifecycleManager,
            VirtualBlockManager virtualBlockManager
    ) {
        this.lifecycleManager = lifecycleManager;
        this.virtualBlockManager = virtualBlockManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (lifecycleManager.interact(event)) {
            return;
        }

        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        UUID renderedModelId = virtualBlockManager.getInstanceIdAt(event.getClickedBlock().getLocation());
        MultiBlockInstance byRenderId = lifecycleManager.findByRenderedModelId(renderedModelId);
        if (byRenderId == null) {
            return;
        }

        if (lifecycleManager.interact(event.getPlayer(), byRenderId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        MultiBlockInstance originInstance = lifecycleManager.findByLocation(block);
        if (originInstance != null) {
            lifecycleManager.remove(originInstance.id(), event.getPlayer(), true);
            event.setDropItems(false);
            return;
        }

        UUID renderedModelId = virtualBlockManager.getInstanceIdAt(block.getLocation());
        MultiBlockInstance byRenderId = lifecycleManager.findByRenderedModelId(renderedModelId);
        if (byRenderId != null) {
            lifecycleManager.remove(byRenderId.id(), event.getPlayer(), true);
            event.setDropItems(false);
        }
    }
}
