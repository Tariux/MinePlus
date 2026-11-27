package com.mineplus.infrastructure.collision.source;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HYBRID occupancy: a base source (usually {@link AutoOccupancySource}) with a
 * blueprint overriding selected cells.
 *
 * <p>Blueprint {@code SOLID}, {@code TOP_SURFACE}, {@code STEP} and
 * {@code FORCE_BLOCK} fill a whole block cell; {@code EMPTY} and
 * {@code PASSABLE_HITBOX} clear it. Cells the blueprint does not mention keep
 * the base source's occupancy. Grids may differ in size: the result is the
 * union of both sizes, with the base grid anchored at the origin.</p>
 */
public final class HybridOccupancySource implements OccupancySource {

    private final OccupancySource base;
    private final BlueprintOccupancySource overlay;

    public HybridOccupancySource(OccupancySource base, BlueprintOccupancySource overlay) {
        if (base == null || overlay == null) {
            throw new IllegalArgumentException("hybrid source needs a base and a blueprint");
        }
        this.base = base;
        this.overlay = overlay;
    }

    @Override
    public OccupancyResult build() {
        OccupancySource.OccupancyResult baseResult = base.build();
        OccupancySource.OccupancyResult overlayResult = overlay.build();
        Blueprint blueprint = overlay.blueprint();
        OccupancyMask baseMask = baseResult.mask();
        int sizeX = Math.max(baseMask.blocksX(), blueprint.sizeX());
        int sizeY = Math.max(baseMask.blocksY(), blueprint.sizeY());
        int sizeZ = Math.max(baseMask.blocksZ(), blueprint.sizeZ());
        OccupancyMask merged = expand(baseMask, sizeX, sizeY, sizeZ);
        int resolution = merged.resolution();

        for (Map.Entry<Long, CellDirective> entry : blueprint.directives().cells().entrySet()) {
            int[] cell = coordinates(entry.getKey(), blueprint);
            CellDirective directive = entry.getValue();
            int x = cell[0];
            int y = cell[1];
            int z = cell[2];
            switch (directive.type()) {
                case SOLID, TOP_SURFACE, STEP, FORCE_BLOCK -> merged.fill(
                        x * resolution, y * resolution, z * resolution,
                        (x + 1) * resolution - 1, (y + 1) * resolution - 1,
                        (z + 1) * resolution - 1, true);
                case EMPTY, PASSABLE_HITBOX -> merged.clear(
                        x * resolution, y * resolution, z * resolution,
                        (x + 1) * resolution - 1, (y + 1) * resolution - 1,
                        (z + 1) * resolution - 1);
            }
        }

        List<String> warnings = new ArrayList<>(baseResult.warnings());
        warnings.addAll(overlayResult.warnings());
        return new OccupancyResult(merged, blueprint.directives(), warnings);
    }

    private static int[] coordinates(long key, Blueprint blueprint) {
        int x = (int) (key % blueprint.sizeX());
        long rest = key / blueprint.sizeX();
        int z = (int) (rest % blueprint.sizeZ());
        int y = (int) (rest / blueprint.sizeZ());
        return new int[] {x, y, z};
    }

    private static OccupancyMask expand(OccupancyMask source, int sizeX, int sizeY, int sizeZ) {
        if (source.blocksX() == sizeX && source.blocksY() == sizeY && source.blocksZ() == sizeZ) {
            return source.copy();
        }
        OccupancyMask expanded = OccupancyMask.empty(source.resolution(), sizeX, sizeY, sizeZ);
        for (int y = 0; y < source.cellsY(); y++) {
            for (int z = 0; z < source.cellsZ(); z++) {
                for (int x = 0; x < source.cellsX(); x++) {
                    if (source.get(x, y, z)) {
                        expanded.set(x, y, z, true);
                    }
                }
            }
        }
        return expanded;
    }

    @Override
    public String describe() {
        return "HYBRID(" + base.describe() + " + " + overlay.describe() + ')';
    }
}
