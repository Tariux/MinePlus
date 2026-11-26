package com.mineplus.machine;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Derives each water wheel's SU from the flowing water around it
 * (Create-style) — event-driven: the scan re-runs for a wheel only when a
 * water block near it changes (flow, drain, bucket use), not on a timer.
 */
final class WaterFlowTask implements Listener {

    private static final int BASE_SU = 8;

    private final MineplusMachinePlugin feature;
    /** Wheels whose SU is already correct for the current water count — skipped on redundant events. */
    private final Set<UUID> settled = new HashSet<>();

    WaterFlowTask(MineplusMachinePlugin feature) {
        this.feature = feature;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (event.getBlock().getType() == Material.WATER || event.getToBlock().getType() == Material.WATER) {
            markDirty(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        markDirty(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        markDirty(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (event.getSourceBlock().getType() == Material.WATER) {
            markDirty(event.getBlock());
        }
    }

    /** Recomputes SU for every wheel whose shell touches the changed block. */
    private void markDirty(Block changed) {
        var rotation = feature.rotationNetworks();
        Collection<MultiBlockInstance> instances = rotation.placedSources();
        for (MultiBlockInstance wheel : instances) {
            if (!settled.add(wheel.id())) {
                continue;
            }
            var at = wheel.coordinate();
            if (!at.worldName().equals(changed.getWorld().getName())) {
                continue;
            }
            int dx = changed.getX() - at.x();
            int dy = changed.getY() - at.y();
            int dz = changed.getZ() - at.z();
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1
                    && (dx != 0 || dy != 0 || dz != 0)) {
                int water = countWater(wheel);
                rotation.setSourceSu(wheel, BASE_SU + water * suPerWater(wheel.typeId()));
            }
        }
    }

    /** Initial (re)scan for one wheel — used on place and after server restart. */
    void rescan(MultiBlockInstance wheel) {
        settled.remove(wheel.id());
        var rotation = feature.rotationNetworks();
        int water = countWater(wheel);
        rotation.setSourceSu(wheel, BASE_SU + water * suPerWater(wheel.typeId()));
    }

    private int suPerWater(String typeId) {
        return switch (typeId) {
            case "water_wheel_weak" -> 16;
            case "water_wheel" -> 32;
            case "water_wheel_strong" -> 64;
            default -> 0;
        };
    }

    private int countWater(MultiBlockInstance wheel) {
        var at = wheel.coordinate();
        World world = org.bukkit.Bukkit.getWorld(at.worldName());
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Block block = world.getBlockAt(at.x() + dx, at.y() + dy, at.z() + dz);
                    if (block.getType() == Material.WATER) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
