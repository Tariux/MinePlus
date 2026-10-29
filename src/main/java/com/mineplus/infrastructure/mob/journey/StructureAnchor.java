package com.mineplus.infrastructure.mob.journey;

import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Bell;
import org.bukkit.block.BlockState;
import org.bukkit.block.Bed;

/**
 * Turns a structure's reported surface coordinate into the point a mob should
 * actually walk to.
 *
 * <p>{@code locateNearestStructure} answers with a generated feature's
 * reference position, not with a navigable centre: for a village it is often a
 * well, a lamp post or the middle of a roof, and for a fortress it can be a
 * staircase inside the wall. A journey aimed at that coordinate routinely ends
 * with the mob circling a wall or standing on a roof it cannot climb.
 *
 * <p>A village has a natural centre: its bell. The meeting point is where the
 * bell hangs, and every village places one when it generates. The anchor
 * therefore prefers the bell closest to the reported position, falls back to
 * the centroid of the village's beds when no bell survived (a raided village
 * loses its bell before its beds), and only then keeps the raw coordinate,
 * normalised to standable ground by {@link GroundProbe}.
 *
 * <p>Chunk scans are limited to chunks that are already loaded — anchoring must
 * never force-generate a region just to answer a question about it. The caller
 * ({@link StructureLookup}) caches the answer per region, so a crew of pilgrims
 * pays for one scan.
 *
 * <p>Thread model: main thread only (chunk access is).
 */
public final class StructureAnchor {

    /** How far around the reported position a village centre is searched, in chunks. */
    private static final int BELL_RADIUS_CHUNKS = 4;

    private StructureAnchor() {
    }

    /**
     * The point a mob should walk to for {@code structureKey} reported at
     * {@code raw}.
     *
     * @return a standable location when one could be resolved, otherwise the
     *         raw coordinate (never {@code null} for a non-null {@code raw})
     */
    public static Location anchor(World world, String structureKey, Location raw) {
        if (raw == null) {
            return null;
        }
        if (world == null || raw.getWorld() != world) {
            return raw;
        }
        Location centre = "village".equalsIgnoreCase(structureKey) ? villageCentre(world, raw) : null;
        Location target = centre != null ? centre : raw;
        Location standable = GroundProbe.standableNear(target, 5, 12);
        return standable != null ? standable : target;
    }

    /**
     * The village's bell, or the centroid of its beds, within
     * {@link #BELL_RADIUS_CHUNKS} of the reported position.
     *
     * @return the centre, or {@code null} when the loaded neighbourhood holds
     *         neither a bell nor a bed (an unloaded or barren village)
     */
    private static Location villageCentre(World world, Location raw) {
        int baseChunkX = raw.getBlockX() >> 4;
        int baseChunkZ = raw.getBlockZ() >> 4;
        List<Location> beds = new ArrayList<>();
        Location bestBell = null;
        double bestBellDistance = Double.MAX_VALUE;
        for (int dx = -BELL_RADIUS_CHUNKS; dx <= BELL_RADIUS_CHUNKS; dx++) {
            for (int dz = -BELL_RADIUS_CHUNKS; dz <= BELL_RADIUS_CHUNKS; dz++) {
                int chunkX = baseChunkX + dx;
                int chunkZ = baseChunkZ + dz;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                for (BlockState state : chunk.getTileEntities()) {
                    Location at = state.getLocation();
                    if (state instanceof Bell) {
                        double distance = raw.distanceSquared(at);
                        if (distance < bestBellDistance) {
                            bestBellDistance = distance;
                            bestBell = at;
                        }
                    } else if (state instanceof Bed) {
                        beds.add(at);
                    }
                }
            }
        }
        if (bestBell != null) {
            return bestBell;
        }
        if (beds.isEmpty()) {
            return null;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        for (Location bed : beds) {
            x += bed.getX();
            y += bed.getY();
            z += bed.getZ();
        }
        double count = beds.size();
        return new Location(world, x / count, y / count, z / count);
    }
}
