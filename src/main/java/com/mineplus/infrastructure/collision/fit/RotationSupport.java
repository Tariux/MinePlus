package com.mineplus.infrastructure.collision.fit;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import com.mineplus.infrastructure.collision.source.CellDirective;
import com.mineplus.infrastructure.collision.source.DirectiveGrid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a yaw to an occupancy grid (and its blueprint cells) before fitting.
 *
 * <p>Quarter turns are lossless and remap directives; other angles resample the
 * grid and drop per-cell directives (the callback reports that as a warning), so
 * a rotated blueprint is only exact at 90° steps.</p>
 */
final class RotationSupport {

    private RotationSupport() {
    }

    record Rotated(OccupancyMask mask, DirectiveGrid directives, double yaw) {
    }

    static Rotated apply(OccupancyMask mask, DirectiveGrid directives, double yawDegrees,
            FitterOptions.RotationSnap snap) {
        double yaw = snap.snap(yawDegrees);
        int quarters = (int) Math.round(yaw / 90.0);
        boolean quarter = Math.abs(yaw - quarters * 90.0) < 1.0e-6;
        OccupancyMask rotated = mask.copy();
        if (quarter) {
            rotated.rotateY90(quarters);
            return new Rotated(rotated, rotateDirectives(directives, quarters), yaw);
        }
        rotated.rotateYaw(yaw);
        DirectiveGrid dropped = DirectiveGrid.empty(rotated.blocksX(), rotated.blocksY(),
                rotated.blocksZ());
        return new Rotated(rotated, dropped, yaw);
    }

    private static DirectiveGrid rotateDirectives(DirectiveGrid source, int quarters) {
        int turns = Math.floorMod(quarters, 4);
        if (turns == 0 || source == null || source.isEmpty()) {
            return source == null
                    ? DirectiveGrid.empty(1, 1, 1)
                    : source;
        }
        int oldX = source.sizeX();
        int oldY = source.sizeY();
        int oldZ = source.sizeZ();
        boolean odd = turns % 2 == 1;
        int newX = odd ? oldZ : oldX;
        int newY = oldY;
        int newZ = odd ? oldX : oldZ;
        Map<Long, CellDirective> moved = new LinkedHashMap<>();
        for (Map.Entry<Long, CellDirective> entry : source.cells().entrySet()) {
            int[] cell = source.coordinates(entry.getKey());
            int x = cell[0];
            int y = cell[1];
            int z = cell[2];
            int nx;
            int nz;
            switch (turns) {
                case 1 -> {
                    nx = oldZ - 1 - z;
                    nz = x;
                }
                case 2 -> {
                    nx = oldX - 1 - x;
                    nz = oldZ - 1 - z;
                }
                default -> {
                    nx = z;
                    nz = oldX - 1 - x;
                }
            }
            long key = ((long) y * newZ + nz) * newX + nx;
            moved.put(key, entry.getValue());
        }
        return new DirectiveGrid(newX, newY, newZ, moved);
    }
}
