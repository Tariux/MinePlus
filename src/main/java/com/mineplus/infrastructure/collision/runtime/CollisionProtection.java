package com.mineplus.infrastructure.collision.runtime;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.block.BlockState;

/**
 * Keeps technical collision parts out of the normal block lifecycle.
 *
 * <p>Parts are real block states the server sees, so vanilla mechanics would
 * happily break them, push them with pistons or explode them; the resulting
 * drops would leak invisible technical blocks into inventories. Everything here
 * runs at {@link EventPriority#LOWEST} so protection wins before other plugins
 * decide, and nothing is cancelled that does not belong to a model.
 */
public final class CollisionProtection implements Listener {

    private final CollisionManager manager;

    public CollisionProtection(CollisionManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesTechnical(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesTechnical(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(manager::isTechnical);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(manager::isTechnical);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (manager.isTechnical(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (manager.isTechnical(event.getBlockClicked())
                || manager.isTechnical(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        List<BlockState> states = new ArrayList<>(event.getBlocks());
        states.removeIf(state -> !manager.isTechnical(state.getBlock()));
        if (states.size() != event.getBlocks().size()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (manager.isTechnicalEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean movesTechnical(List<Block> blocks) {
        for (Block block : blocks) {
            if (manager.isTechnical(block)) {
                return true;
            }
        }
        return false;
    }
}
