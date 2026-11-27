package com.mineplus.infrastructure.collision.bukkit;

import java.util.List;
import org.bukkit.block.data.BlockData;

/**
 * Version seam that reads the collision shape of one block state.
 *
 * <p>Two implementations cover the whole 1.21–26.2 range:
 * {@link PaperShapeReader} (Paper's contextual {@code BlockData} API) and
 * {@link ScratchShapeReader} (a temporary block in a scratch location, read
 * through {@code Block#getCollisionShape()}). Implementations must be used on
 * the thread that owns the scratch world.</p>
 */
public interface ShapeReader extends AutoCloseable {

    /**
     * Reads the shape.
     *
     * @param data the state to measure
     * @return boxes in block-local units ({@code 0..1}) plus derived flags
     */
    ShapeSample read(BlockData data);

    /** True when reading never touches the world (safe anywhere). */
    boolean worldFree();

    /** Releases any scratch block. */
    @Override
    void close();

    /**
     * Raw shape data for one state.
     *
     * @param boxes       collision boxes as {@code {minX,minY,minZ,maxX,maxY,maxZ}}
     * @param hasOutline  whether the state has a selection outline
     */
    record ShapeSample(List<double[]> boxes, boolean hasOutline) {
    }
}
