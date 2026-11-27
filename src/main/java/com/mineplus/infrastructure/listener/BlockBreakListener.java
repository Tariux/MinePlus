package com.mineplus.infrastructure.listener;

import com.mineplus.infrastructure.core.block.BlockActionRunner;
import com.mineplus.infrastructure.core.block.BlockBreakTracker;
import com.mineplus.infrastructure.core.block.BlockBreakingRules;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import com.mineplus.infrastructure.core.block.BlockPlacementService;
import com.mineplus.infrastructure.core.block.BlockRegistry;
import com.mineplus.infrastructure.core.block.BlockStore;
import com.mineplus.infrastructure.core.block.PlacedBlock;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** Hardness, tool gating and destroy stages for placed blocks. */
public final class BlockBreakListener implements Listener {

    private final VirtualBlockManager manager;
    private final BlockRegistry registry;
    private final BlockStore store;
    private final BlockPlacementService placement;
    private final BlockBreakTracker tracker = new BlockBreakTracker();
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    public BlockBreakListener(
            VirtualBlockManager manager,
            BlockRegistry registry,
            BlockStore store,
            BlockPlacementService placement
    ) {
        this.manager = manager;
        this.registry = registry;
        this.store = store;
        this.placement = placement;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        UUID instanceId = manager.getInstanceIdAt(event.getBlock().getLocation());
        if (instanceId == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        PlacedBlock record = store.get(instanceId);
        if (record == null) {
            return;
        }
        BlockDefinition definition = registry.get(record.definitionId());
        if (definition == null) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Material material = held == null ? Material.AIR : held.getType();
        String refusal = BlockBreakingRules.refusal(definition.breaking().tool(), material);
        event.setCancelled(true);
        if (refusal != null) {
            warn(player, refusal);
            return;
        }
        double speed = BlockBreakingRules.speed(BlockBreakingRules.tierOf(material));
        BlockBreakTracker.State state = tracker.damage(
                player.getUniqueId(), instanceId, definition.breaking().hardness(), speed,
                System.currentTimeMillis());
        for (Location cell : manager.getBarrierLocations(instanceId)) {
            player.sendBlockDamage(cell, (float) state.fraction());
        }
        if (!state.complete()) {
            return;
        }
        tracker.reset(player.getUniqueId(), instanceId);
        ItemStack drop = placement.dropFor(record);
        Location origin = event.getBlock().getLocation();
        if (drop != null && origin.getWorld() != null) {
            origin.getWorld().dropItemNaturally(origin.clone().add(0.5, 0.5, 0.5), drop);
        }
        manager.removeModel(instanceId);
        placement.forget(instanceId);
        BlockActionRunner.run(player, definition, definition.events().onBreak());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tracker.forgetPlayer(event.getPlayer().getUniqueId());
        lastWarn.remove(event.getPlayer().getUniqueId());
    }

    private void warn(Player player, String message) {
        long now = System.currentTimeMillis();
        Long previous = lastWarn.get(player.getUniqueId());
        if (previous != null && now - previous < 1000L) {
            return;
        }
        lastWarn.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.RED + message);
    }
}
