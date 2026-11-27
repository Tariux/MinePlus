package com.mineplus.infrastructure.collision.atlas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Immutable, ordered registry of block-state collision shapes for one server.
 *
 * <p>Entries are kept sorted by state string so every query, fit and serialised
 * cache is deterministic. Lookup is O(1) by state string. Instances are safe to
 * share between threads once built.</p>
 */
public final class ShapeAtlas {

    private final AtlasKey key;
    private final Map<String, ShapeEntry> byState;
    private final List<ShapeEntry> ordered;

    public ShapeAtlas(AtlasKey key, Collection<ShapeEntry> entries) {
        this.key = key;
        List<ShapeEntry> sorted = new ArrayList<>(entries == null ? List.of() : entries);
        sorted.sort(ShapeEntry::compareTo);
        Map<String, ShapeEntry> index = new LinkedHashMap<>(Math.max(16, sorted.size() * 2));
        for (ShapeEntry entry : sorted) {
            ShapeEntry previous = index.putIfAbsent(entry.state(), entry);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate shape entry for " + entry.state());
            }
        }
        this.ordered = List.copyOf(sorted);
        this.byState = Collections.unmodifiableMap(index);
    }

    /** An empty atlas for the key (used before the first build). */
    public static ShapeAtlas empty(AtlasKey key) {
        return new ShapeAtlas(key, List.of());
    }

    public AtlasKey key() {
        return key;
    }

    /** Exact lookup by full block-state string, or null. */
    public ShapeEntry get(String state) {
        return state == null ? null : byState.get(state.trim());
    }

    /** The first entry of a material, in state order, or null. */
    public ShapeEntry firstOfMaterial(String material) {
        if (material == null) {
            return null;
        }
        String wanted = material.trim();
        for (ShapeEntry entry : ordered) {
            if (entry.material().equalsIgnoreCase(wanted)) {
                return entry;
            }
        }
        return null;
    }

    /** All entries matching the predicate, in deterministic order. */
    public List<ShapeEntry> query(Predicate<ShapeEntry> predicate) {
        if (predicate == null) {
            return ordered;
        }
        List<ShapeEntry> matches = new ArrayList<>();
        for (ShapeEntry entry : ordered) {
            if (predicate.test(entry)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /** Candidates the fitter may use without a visible block appearing. */
    public List<ShapeEntry> invisibleCandidates() {
        List<ShapeEntry> matches = new ArrayList<>();
        for (ShapeEntry entry : ordered) {
            if (entry.invisibleCapable()) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    /** Full ordered entry list (unmodifiable). */
    public List<ShapeEntry> entries() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** A new atlas with the same key and these extra entries merged in. */
    public ShapeAtlas withExtra(Collection<ShapeEntry> extra) {
        List<ShapeEntry> merged = new ArrayList<>(ordered);
        merged.addAll(extra);
        return new ShapeAtlas(key, merged);
    }

    /** Summary counters for {@code /mineplus atlas stats}. */
    public AtlasStats stats() {
        int withCollision = 0;
        int invisible = 0;
        int fullCubes = 0;
        int noCollision = 0;
        long bits = 0;
        int minBits = Integer.MAX_VALUE;
        int maxBits = 0;
        for (ShapeEntry entry : ordered) {
            if (entry.hasCollision()) {
                withCollision++;
            } else {
                noCollision++;
            }
            if (entry.invisibleCapable()) {
                invisible++;
            }
            if (entry.fullCube()) {
                fullCubes++;
            }
            bits += entry.bitCount();
            minBits = Math.min(minBits, entry.bitCount());
            maxBits = Math.max(maxBits, entry.bitCount());
        }
        return new AtlasStats(ordered.size(), withCollision, noCollision, invisible, fullCubes,
                bits, ordered.isEmpty() ? 0 : minBits, maxBits, key.describe());
    }

    /**
     * Atlas counters.
     *
     * @param entries          total candidate states
     * @param withCollision    entries that collide
     * @param noCollision      passable entries
     * @param invisibleCapable entries usable without being seen
     * @param fullCubes        entries that fill their block
     * @param totalBits        sum of solid sub-voxels
     * @param minBits          smallest collision shape
     * @param maxBits          largest collision shape
     * @param key              atlas key description
     */
    public record AtlasStats(int entries, int withCollision, int noCollision,
            int invisibleCapable, int fullCubes, long totalBits, int minBits, int maxBits,
            String key) {
    }
}
