package com.mineplus.infrastructure.collision.bukkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

/**
 * Reads shapes by temporarily placing the state in a scratch block.
 *
 * <p>Portable across Spigot and Paper and the only path on servers without
 * Paper's contextual shape API. The scratch block is placed without physics and
 * restored after every read; the surrounding chunk is expected to be empty
 * (the builder uses a y-level above the build limit). This is a main-thread
 * operation.</p>
 *
 * <p>Reads also strip context-dependent shapes: a fence/wall evaluated in
 * isolation connects to nothing, so the atlas stores the unconnected variant,
 * which is the conservative reading for a model placed in open air.</p>
 */
public final class ScratchShapeReader implements ShapeReader {

    private final Block scratch;
    private final BlockData original;

    public ScratchShapeReader(World world, Location scratchLocation) {
        if (world == null || scratchLocation == null) {
            throw new IllegalArgumentException("scratch reader needs a world and a location");
        }
        this.scratch = world.getBlockAt(scratchLocation);
        this.original = scratch.getBlockData().clone();
    }

    @Override
    public ShapeSample read(BlockData data) {
        scratch.setBlockData(data, false);
        try {
            VoxelShape shape = scratch.getCollisionShape();
            Collection<BoundingBox> boxes = shape.getBoundingBoxes();
            List<double[]> converted = new ArrayList<>(boxes.size());
            for (BoundingBox box : boxes) {
                converted.add(new double[] {
                        clamp(box.getMinX()), clamp(box.getMinY()), clamp(box.getMinZ()),
                        clamp(box.getMaxX()), clamp(box.getMaxY()), clamp(box.getMaxZ())});
            }
            return new ShapeSample(converted, !converted.isEmpty());
        } finally {
            scratch.setBlockData(original, false);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public boolean worldFree() {
        return false;
    }

    @Override
    public void close() {
        scratch.setBlockData(original, false);
    }
}
