package com.mineplus.infrastructure.mob.navigation;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Ground arithmetic for movement decisions: is this block a spot a land mob can
 * actually stand on, and where is the nearest such spot around a target.
 *
 * <p>A journey target is a waypoint the mob must be able to path to. Passing the
 * raw surface coordinate of a structure (or a point read from an unloaded area)
 * routinely names a block that is a roof, a wall or a hole, and vanilla
 * pathfinding then reports no path at all. Normalising the waypoint to a
 * standable cell is what turns "the mob refuses to walk" into a walk.
 */
public final class GroundProbe {

    /** Two air cells over solid ground is the definition of a standable foot block. */
    private static final int CLEARANCE = 2;

    private GroundProbe() {
    }

    /** {@code true} when a land mob can stand in {@code foot} (solid below, two air above). */
    public static boolean isStandable(Block foot) {
        if (foot == null || !foot.getChunk().isLoaded()) {
            return false;
        }
        Block below = foot.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) {
            return false;
        }
        for (int dy = 0; dy < CLEARANCE; dy++) {
            Block candidate = foot.getRelative(BlockFace.UP, dy);
            if (!candidate.isPassable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest standable spot in the column at {@code x}/{@code z}, searched
     * outward from {@code y} by {@code window} blocks (down first, then up —
     * ground is usually below a remembered waypoint, not above it).
     *
     * @return a centred walkable location, or {@code null} when the column is
     *         unloaded or holds no standable cell in range
     */
    public static Location standableColumn(World world, int x, int z, int y, int window) {
        if (world == null || y < world.getMinHeight() || y > world.getMaxHeight()) {
            return null;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        int min = Math.max(world.getMinHeight(), y - window);
        int max = Math.min(world.getMaxHeight() - 1, y + window);
        for (int candidate = y; candidate >= min; candidate--) {
            if (isStandable(world.getBlockAt(x, candidate, z))) {
                return centre(world, x, candidate, z);
            }
        }
        for (int candidate = y + 1; candidate <= max; candidate++) {
            if (isStandable(world.getBlockAt(x, candidate, z))) {
                return centre(world, x, candidate, z);
            }
        }
        return null;
    }

    /**
     * A walkable point near {@code target}, preferring spots close to it. The
     * scan is a small square ring, so a waypoint blocked by one wall still
     * resolves.
     */
    public static Location standableNear(Location target, int radius, int verticalWindow) {
        if (target == null || target.getWorld() == null) {
            return null;
        }
        World world = target.getWorld();
        int baseX = target.getBlockX();
        int baseZ = target.getBlockZ();
        int baseY = target.getBlockY();
        Location exact = standableColumn(world, baseX, baseZ, baseY, verticalWindow);
        if (exact != null) {
            return exact;
        }
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // ring perimeter only; interiors were already tried
                    }
                    Location candidate = standableColumn(world, baseX + dx, baseZ + dz, baseY, verticalWindow);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Ground that can carry a placed block for a camp, bed or structure: open,
     * not liquid, on solid earth, and not on a tree — a torch on a leaf or a bed
     * on a log is a placement that silently fails, which is why a mob "built"
     * nothing and the showcase looked broken.
     */
    public static boolean isCampGround(Block spot) {
        if (spot == null || !spot.getChunk().isLoaded()) {
            return false;
        }
        if (!spot.isPassable() || spot.isLiquid()) {
            return false;
        }
        if (spot.getType() != org.bukkit.Material.AIR) {
            return false;
        }
        Block below = spot.getRelative(BlockFace.DOWN);
        org.bukkit.Material ground = below.getType();
        return ground.isSolid()
                && !org.bukkit.Tag.LEAVES.isTagged(ground)
                && !org.bukkit.Tag.LOGS.isTagged(ground);
    }

    /**
     * The nearest block around {@code centre} that {@link #isCampGround} accepts,
     * ring by ring, within {@code radius} and {@code verticalWindow}.
     *
     * @return the spot, or {@code null} when the neighbourhood holds none
     */
    public static Block campGroundNear(Block centre, int radius, int verticalWindow) {
        if (centre == null) {
            return null;
        }
        if (isCampGround(centre)) {
            return centre;
        }
        org.bukkit.World world = centre.getWorld();
        if (world == null) {
            return null;
        }
        for (int ring = 1; ring <= radius; ring++) {
            for (int dy = -verticalWindow; dy <= verticalWindow; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue; // perimeter only; inner rings were already tried
                        }
                        Block candidate = world.getBlockAt(centre.getX() + dx, centre.getY() + dy,
                                centre.getZ() + dz);
                        if (isCampGround(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Location centre(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }
}
