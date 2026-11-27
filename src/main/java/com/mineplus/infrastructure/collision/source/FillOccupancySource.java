package com.mineplus.infrastructure.collision.source;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import java.util.List;

/** FILL occupancy: the whole bounding box (optionally inset) is solid. */
public final class FillOccupancySource implements OccupancySource {

    private final int resolution;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final double insetBlocks;

    /**
     * @param resolution  sub-voxels per block
     * @param sizeX       blocks on X
     * @param sizeY       blocks on Y
     * @param sizeZ       blocks on Z
     * @param insetBlocks shrink on every side, in blocks (0 = exact box)
     */
    public FillOccupancySource(int resolution, int sizeX, int sizeY, int sizeZ,
            double insetBlocks) {
        this.resolution = Math.max(1, Math.min(64, resolution));
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.insetBlocks = Math.max(0, insetBlocks);
    }

    @Override
    public OccupancyResult build() {
        OccupancyMask mask = OccupancyMask.empty(resolution, sizeX, sizeY, sizeZ);
        int inset = (int) Math.round(insetBlocks * resolution);
        int maxX = sizeX * resolution - 1;
        int maxY = sizeY * resolution - 1;
        int maxZ = sizeZ * resolution - 1;
        if (inset * 2 >= Math.min(sizeX * resolution, Math.min(sizeY * resolution,
                sizeZ * resolution))) {
            return new OccupancyResult(mask,
                    DirectiveGrid.empty(mask.blocksX(), mask.blocksY(), mask.blocksZ()),
                    List.of("fill inset " + insetBlocks + " blocks leaves nothing; occupancy empty"));
        }
        mask.fill(inset, inset, inset, maxX - inset, maxY - inset, maxZ - inset, true);
        return new OccupancyResult(mask,
                DirectiveGrid.empty(mask.blocksX(), mask.blocksY(), mask.blocksZ()), List.of());
    }

    @Override
    public String describe() {
        return "FILL(" + sizeX + "×" + sizeY + "×" + sizeZ + " @ " + resolution
                + (insetBlocks > 0 ? ", inset " + insetBlocks : "") + ')';
    }
}
