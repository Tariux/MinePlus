package com.mineplus.infrastructure.collision.source;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blueprint directives keyed by block cell, in the occupancy grid's block space.
 *
 * <p>Immutable; cells outside the grid are ignored by the fitter. Keys are
 * packed as {@code (y * sizeZ + z) * sizeX + x} in blocks.</p>
 */
public final class DirectiveGrid {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Map<Long, CellDirective> cells;

    public DirectiveGrid(int sizeX, int sizeY, int sizeZ, Map<Long, CellDirective> cells) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = Collections.unmodifiableMap(
                new LinkedHashMap<>(cells == null ? Map.of() : cells));
    }

    public static DirectiveGrid empty(int sizeX, int sizeY, int sizeZ) {
        return new DirectiveGrid(sizeX, sizeY, sizeZ, Map.of());
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int size() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public Map<Long, CellDirective> cells() {
        return cells;
    }

    /** Directive for a block cell, or null when the blueprint is silent. */
    public CellDirective get(int blockX, int blockY, int blockZ) {
        CellDirective directive = cells.get(key(blockX, blockY, blockZ));
        return directive;
    }

    /** True when the cell has a directive of this type. */
    public boolean has(int blockX, int blockY, int blockZ, DirectiveType type) {
        CellDirective directive = get(blockX, blockY, blockZ);
        return directive != null && directive.type() == type;
    }

    /** Resolves a packed cell key back to coordinates (for debug output). */
    public int[] coordinates(long key) {
        int x = (int) (key % sizeX);
        long rest = key / sizeX;
        int z = (int) (rest % sizeZ);
        int y = (int) (rest / sizeZ);
        return new int[] {x, y, z};
    }

    /** Packs block coordinates into a stable key. */
    public long key(int blockX, int blockY, int blockZ) {
        return (long) blockY * sizeZ * sizeX + (long) blockZ * sizeX + blockX;
    }

    /** All directives in insertion order (deterministic for a given blueprint). */
    public Collection<CellDirective> values() {
        return cells.values();
    }
}
