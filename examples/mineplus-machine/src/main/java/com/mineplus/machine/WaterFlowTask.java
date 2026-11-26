package com.mineplus.machine;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Derives each water wheel's SU from the flowing water around it
 * (Create-style): every second, each placed source wheel scans its
 * 3x3 shell for water blocks and pushes the count through the core
 * service's dynamic-SU hook, so a dry wheel barely turns and a wheel
 * in a river runs at full strength.
 */
final class WaterFlowTask extends BukkitRunnable {

    private static final int BASE_SU = 8;

    private final MineplusMachinePlugin feature;

    WaterFlowTask(MineplusMachinePlugin feature) {
        this.feature = feature;
    }

    @Override
    public void run() {
        var rotation = feature.rotationNetworks();
        Collection<MultiBlockInstance> instances = rotation.placedSources();
        for (MultiBlockInstance wheel : instances) {
            int water = countWater(wheel);
            rotation.setSourceSu(wheel, BASE_SU + water * suPerWater(wheel.typeId()));
        }
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
