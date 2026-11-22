package com.mineplus.infrastructure.core.multiblock.formation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Matches {@link FormationPattern}s against real world blocks.
 *
 * <p>The matcher tries the pattern in all four 90° yaw rotations (unless the
 * pattern is {@link FormationPattern#rotationLocked()}); the anchor offset
 * {@code 0,0,0} is the block the caller reports. A rotation matches when every
 * pattern cell's material equals the world block at anchor + rotated offset.
 */
public final class FormationMatcher {

    private FormationMatcher() {
    }

    /**
     * Result of a successful match: the rotation index used (0-3, in 90° steps)
     * and the world corners of the matched bounding box.
     */
    public record Match(int rotation, Location minCorner, Location maxCorner) {
    }

    /** Returns the first rotation that matches at {@code anchor}, or {@code null}. */
    public static Match matchAt(FormationPattern pattern, Location anchor) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        int startX = anchor.getBlockX();
        int startY = anchor.getBlockY();
        int startZ = anchor.getBlockZ();

        int rotations = pattern.rotationLocked() ? 1 : 4;
        for (int rotation = 0; rotation < rotations; rotation++) {
            boolean ok = true;
            int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
            for (Map.Entry<String, String> cell : pattern.cells().entrySet()) {
                String[] parts = cell.getKey().split(",");
                int ox = Integer.parseInt(parts[0]);
                int oy = Integer.parseInt(parts[1]);
                int oz = Integer.parseInt(parts[2]);

                int rx = ox;
                int rz = oz;
                switch (rotation) {
                    case 1 -> { rx = -oz; rz = ox; }
                    case 2 -> { rx = -ox; rz = -oz; }
                    case 3 -> { rx = oz; rz = -ox; }
                    default -> { }
                }

                Material expected = Material.matchMaterial(cell.getValue());                if (expected == null) {
                    ok = false;
                    break;
                }
                Material actual = world.getBlockAt(startX + rx, startY + oy, startZ + rz).getType();
                if (actual != expected) {
                    ok = false;
                    break;
                }
                minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
                minY = Math.min(minY, oy); maxY = Math.max(maxY, oy);
                minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
            }
            if (ok) {
                return new Match(rotation,
                        new Location(world, startX + minX, startY + minY, startZ + minZ),
                        new Location(world, startX + maxX, startY + maxY, startZ + maxZ));
            }
        }
        return null;
    }

    /** Convenience: the rotation offsets for a rotation index, shared with preview rendering. */
    public static Map<String, int[]> rotatedOffsets(FormationPattern pattern, int rotation) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (String key : pattern.cells().keySet()) {
            String[] parts = key.split(",");
            int ox = Integer.parseInt(parts[0]);
            int oy = Integer.parseInt(parts[1]);
            int oz = Integer.parseInt(parts[2]);
            int rx = ox;
            int rz = oz;
            switch (rotation) {
                case 1 -> { rx = -oz; rz = ox; }
                case 2 -> { rx = -ox; rz = -oz; }
                case 3 -> { rx = oz; rz = -ox; }
                default -> { }
            }
            out.put(key, new int[] { rx, oy, rz });
        }
        return Collections.unmodifiableMap(out);
    }
}
