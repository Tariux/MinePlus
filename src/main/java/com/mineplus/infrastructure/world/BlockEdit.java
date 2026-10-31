package com.mineplus.infrastructure.world;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * One deferred block write: the target cell, the material state to apply, the
 * physics flag, and the drain priority.
 *
 * <p>The {@link BlockData} is a detached snapshot taken by the caller, so the
 * edit can be applied later without touching the world on the producing
 * thread. A queue never reads the live block except when it applies the
 * edit.</p>
 *
 * @param x        block X
 * @param y        block Y
 * @param z        block Z
 * @param data     the state to apply
 * @param physics  whether the write triggers vanilla physics
 * @param priority drain order; {@link Priority#HIGH} strictly before {@link Priority#LOW}
 */
public record BlockEdit(int x, int y, int z, BlockData data, boolean physics, Priority priority) {

    /** Drain order bucket. FIFO is preserved inside each bucket. */
    public enum Priority {
        HIGH,
        LOW
    }

    public BlockEdit {
        if (data == null) {
            throw new IllegalArgumentException("BlockEdit data is required");
        }
        if (priority == null) {
            priority = Priority.LOW;
        }
    }

    /** An edit of one block's current position, physics on, low priority. */
    public static BlockEdit of(Block block, BlockData data) {
        return new BlockEdit(block.getX(), block.getY(), block.getZ(), data, true, Priority.LOW);
    }

    /** An edit of one block's current position. */
    public static BlockEdit of(Block block, BlockData data, boolean physics, Priority priority) {
        return new BlockEdit(block.getX(), block.getY(), block.getZ(), data, physics, priority);
    }

    public static BlockEdit of(int x, int y, int z, BlockData data) {
        return new BlockEdit(x, y, z, data, true, Priority.LOW);
    }

    public static BlockEdit of(int x, int y, int z, BlockData data, boolean physics, Priority priority) {
        return new BlockEdit(x, y, z, data, physics, priority);
    }

    /** A removal edit (air, no physics) at the given cell. */
    public static BlockEdit air(int x, int y, int z) {
        return new BlockEdit(x, y, z, Material.AIR.createBlockData(), false, Priority.LOW);
    }
}
