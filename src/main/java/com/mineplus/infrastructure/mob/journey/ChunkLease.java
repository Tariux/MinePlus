package com.mineplus.infrastructure.mob.journey;

import java.lang.reflect.Method;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * A plugin chunk ticket held for the chunk a journey's next leg crosses, so a
 * long march is not paused every time the mob leaves player view distance.
 *
 * <p>Without a ticket the mob's chunk unloads behind the last player, the
 * entity stops being resolvable, and the journey only resumes when somebody
 * happens to wander past. The ticket keeps exactly one chunk loaded per
 * journey (the leg the mob is walking now), released as soon as the leg
 * changes or the journey ends, and the runner caps how many it holds.
 *
 * <p>Plugin chunk tickets are Paper API. The seam resolves both methods once;
 * on a server without them journeys simply behave as before.
 *
 * <p>Thread model: main thread only.
 */
final class ChunkLease {

    private static final Method ADD = method("addPluginChunkTicket");
    private static final Method REMOVE = method("removePluginChunkTicket");

    private ChunkLease() {
    }

    static boolean available() {
        return ADD != null && REMOVE != null;
    }

    static void add(World world, int chunkX, int chunkZ, Plugin plugin) {
        invoke(ADD, world, chunkX, chunkZ, plugin);
    }

    static void remove(World world, int chunkX, int chunkZ, Plugin plugin) {
        invoke(REMOVE, world, chunkX, chunkZ, plugin);
    }

    private static void invoke(Method method, World world, int chunkX, int chunkZ, Plugin plugin) {
        if (method == null || world == null || plugin == null) {
            return;
        }
        try {
            method.invoke(world, chunkX, chunkZ, plugin);
        } catch (Throwable unavailable) {
            // A regionised or older server may refuse; the journey still walks,
            // it just does not hold the chunk.
        }
    }

    private static Method method(String name) {
        try {
            return World.class.getMethod(name, int.class, int.class, Plugin.class);
        } catch (Throwable missing) {
            return null;
        }
    }
}
