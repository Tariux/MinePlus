package com.mineplus.infrastructure.mob.journey;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.StructureType;
import org.bukkit.World;

/**
 * Cached, cost-gated "what is the nearest village / ocean monument / fortress"
 * for the mob subsystem.
 *
 * <p>{@link World#locateNearestStructure} is a world-generation query, not a
 * memory lookup: it walks the feature index for the requested square, and it is
 * main-thread only. A mob that asks for it on every evaluation is the one way a
 * behavior showcase can stall the tick. So the Core owns the question and makes
 * it cheap:
 * <ul>
 *   <li><b>Escalating radius</b> — the search starts small and doubles until it
 *       hits or the caller's ceiling is exceeded. A village around the corner is
 *       found in a 4-chunk sweep instead of a 64-chunk one.</li>
 *   <li><b>Region cache</b> — results are keyed by world, structure and a coarse
 *       origin bucket, so a crew of twelve villagers in one wasteland pays for
 *       one search, not twelve. Negative results are cached too (shorter), which
 *       is what turns "barren region" from a repeated stall into a stated fact.</li>
 *   <li><b>Per-key cooldown</b> — a region is re-probed at most once per
 *       {@link #REPROBE_COOLDOWN_TICKS}, however often mobs in it ask.</li>
 *   <li><b>Bounded map</b> — stale entries are pruned on read, so a long-running
 *       server cannot accumulate one entry per coordinate a mob ever stood on.</li>
 *   <li><b>Usable centres</b> — the raw answer is only a feature reference
 *       point. {@link StructureAnchor} converts it into the point a mob can
 *       actually walk to (a village's bell, its bed centroid, or a standable
 *       cell next to the reported position), and the cache stores that.</li>
 * </ul>
 *
 * <p>Thread model: main thread only (the behavior engine and force-fire are).
 */
public final class StructureLookup {

    /** Smallest sweep, in chunks. Doubles per retry up to the caller's ceiling. */
    private static final int BASE_RADIUS_CHUNKS = 4;
    /** A region is not re-probed more often than this, even when the cache expired. */
    private static final long REPROBE_COOLDOWN_TICKS = 200L;
    /** A positive result stays believable for this long. */
    private static final long FOUND_TTL_TICKS = 6000L;
    /** A negative result is re-checked sooner — the world does not change, but radii do. */
    private static final long MISSING_TTL_TICKS = 1200L;
    /** Cached regions held at once. */
    private static final int MAX_ENTRIES = 256;

    private static final class Entry {
        Location location;
        int radiusChunks;
        long foundAtTick;
        long nextProbeAtTick;
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * The nearest structure of {@code structureKey} (e.g. {@code village},
     * {@code nether_fortress}) within {@code maxRadiusChunks} of {@code origin}.
     *
     * @return the structure's location, or {@code null} when nothing was found
     *         within the ceiling — including when the search is still cooling
     *         down, which the caller should read as "keep waiting", not
     *         "there is nothing here"
     */
    public Location nearest(World world, String structureKey, Location origin, int maxRadiusChunks) {
        return nearest(world, structureKey, origin, maxRadiusChunks, true);
    }

    /**
     * Same as {@link #nearest(World, String, Location, int)} with the reported
     * sweep radius available to the caller (status lines, debug logs).
     */
    public Location nearest(World world, String structureKey, Location origin, int maxRadiusChunks,
                            boolean includeUnknown) {
        if (world == null || origin == null || structureKey == null || structureKey.isBlank()) {
            return null;
        }
        String normalized = structureKey.toLowerCase(Locale.ROOT);
        StructureType structure = structure(normalized);
        if (structure == null) {
            return null;
        }
        int ceiling = Math.max(BASE_RADIUS_CHUNKS, Math.min(256, maxRadiusChunks));
        long now = world.getGameTime();
        String key = regionKey(world, normalized, origin);
        Entry entry = cache.get(key);

        if (entry != null) {
            boolean fresh = now < entry.foundAtTick + (entry.location == null ? MISSING_TTL_TICKS : FOUND_TTL_TICKS);
            // A cached hit at a radius that already reaches the caller's answer
            // is the whole point of the cache; a cached miss only answers for
            // the sweep it actually covered.
            if (fresh && (entry.location != null || entry.radiusChunks >= ceiling)) {
                return entry.location;
            }
            if (now < entry.nextProbeAtTick) {
                return entry.location;
            }
        }
        if (cache.size() > MAX_ENTRIES) {
            prune(now);
        }
        return search(world, normalized, origin, structure, key, ceiling, now, includeUnknown);
    }

    /**
     * One escalating sweep. Doubles the radius from {@link #BASE_RADIUS_CHUNKS}
     * until a hit or the ceiling, so the cost tracks the answer rather than the
     * caller's worst case.
     *
     * @param includeUnknown probe past the chunks players have explored — most
     *                       "no village found" answers are really "the village
     *                       is unexplored". It is the last resort at the full
     *                       ceiling because that pass marks chunks explored.
     */
    private Location search(World world, String structureKey, Location origin, StructureType structure, String key,
                            int ceiling, long now, boolean includeUnknown) {
        for (int radius = BASE_RADIUS_CHUNKS; ; radius *= 2) {
            int sweep = Math.min(radius, ceiling);
            Location found = probe(world, origin, structure, sweep, false);
            if (found != null) {
                Location anchored = StructureAnchor.anchor(world, structureKey, found);
                remember(key, anchored, sweep, now);
                return anchored;
            }
            if (sweep >= ceiling) {
                break;
            }
        }
        if (includeUnknown) {
            Location beyond = probe(world, origin, structure, ceiling, true);
            if (beyond != null) {
                Location anchored = StructureAnchor.anchor(world, structureKey, beyond);
                remember(key, anchored, ceiling, now);
                return anchored;
            }
        }
        remember(key, null, ceiling, now);
        return null;
    }

    private static Location probe(World world, Location origin, StructureType structure, int radiusChunks,
                                  boolean findUnexplored) {
        try {
            Location found = world.locateNearestStructure(origin, structure, radiusChunks, findUnexplored);
            return found == null ? null
                    : new Location(found.getWorld(), found.getBlockX(), found.getBlockY(), found.getBlockZ());
        } catch (Throwable unavailable) {
            // Older Spigot builds and ungenerated dimensions both refuse the
            // lookup; the caller simply sees "nothing found".
            return null;
        }
    }

    private void remember(String key, Location found, int radiusChunks, long now) {
        Entry entry = cache.computeIfAbsent(key, k -> new Entry());
        entry.location = found;
        entry.radiusChunks = radiusChunks;
        entry.foundAtTick = now;
        entry.nextProbeAtTick = now + REPROBE_COOLDOWN_TICKS;
    }

    /** Drops entries whose TTL lapsed; keeps the bounded map actually bounded. */
    public void prune(long now) {
        cache.entrySet().removeIf(entry -> {
            Entry value = entry.getValue();
            long ttl = value.location == null ? MISSING_TTL_TICKS : FOUND_TTL_TICKS;
            return now - value.foundAtTick > ttl * 2L;
        });
    }

    /** Cached regions currently held — diagnostics only. */
    public int cachedRegions() {
        return cache.size();
    }

    /**
     * A coarse origin bucket: 16-chunk squares. Two villagers a few hundred
     * blocks apart are asking about the same region and should share one answer.
     */
    private static String regionKey(World world, String structureKey, Location origin) {
        int bucketX = origin.getBlockX() >> 8;   // 16 chunks = 256 blocks
        int bucketZ = origin.getBlockZ() >> 8;
        return world.getName() + '|' + structureKey.toLowerCase(Locale.ROOT) + '|' + bucketX + '|' + bucketZ;
    }

    /** Structure by name, resolved from the server's own table and memoised. */
    private static final Map<String, StructureType> TYPES = new ConcurrentHashMap<>();

    private static StructureType structure(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        StructureType cached = TYPES.get(normalized);
        if (cached != null) {
            return cached;
        }
        StructureType found = StructureType.getStructureTypes().get(normalized);
        if (found == null) {
            return null;
        }
        if (TYPES.size() < 64) {
            TYPES.put(normalized, found);
        }
        return found;
    }
}
