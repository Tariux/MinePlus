package com.mineplus.infrastructure.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockCoordinate(String worldName, int x, int y, int z) {

    public static BlockCoordinate from(Block block) {
        return from(block.getLocation());
    }

    public static BlockCoordinate from(Location location) {
        World world = location.getWorld();
        String worldName = world == null ? "" : world.getName();
        return new BlockCoordinate(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return new Location(world, x, y, z);
    }
}
