package com.mineplus.infrastructure.collision.atlas;

import com.mineplus.infrastructure.collision.mask.VoxelMask;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One candidate block state and the collision shape read from the server.
 *
 * <p>{@code state} is the full block-state string
 * ({@code minecraft:oak_stairs[facing=north,half=bottom,...]}), which is the
 * atlas key and the value used for later parsing. {@code material} is the bare
 * material name for grouping and config.</p>
 *
 * @param state            full block-state string, never blank
 * @param material         Bukkit material name, e.g. {@code OAK_STAIRS}
 * @param mask             the collision shape at 16³ sub-voxels
 * @param bitCount         solid sub-voxels (cached, equals {@code mask.count()})
 * @param collisionBounds  {@code {minX,minY,minZ,maxX,maxZ}} in block units;
 *                         all zero when there is no collision
 * @param hasCollision     whether the state collides at all
 * @param hasOutline       whether it draws a selection outline
 * @param fullCube         whether the collision fills the block cube
 * @param flags            mechanical flags (see {@link ShapeFlag})
 * @param visibleByDefault whether a plain vanilla client sees the state
 * @param invisibleInPack  whether the configured resource pack gives it an
 *                         empty model (making it usable as a ghost or real
 *                         invisible collision block)
 * @param backendCost      relative cost, 0 (cheapest) to 10 (most expensive)
 */
public record ShapeEntry(
        String state,
        String material,
        VoxelMask mask,
        int bitCount,
        double[] collisionBounds,
        boolean hasCollision,
        boolean hasOutline,
        boolean fullCube,
        Set<ShapeFlag> flags,
        boolean visibleByDefault,
        boolean invisibleInPack,
        int backendCost) implements Comparable<ShapeEntry> {

    public ShapeEntry {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("shape entry needs a block state");
        }
        state = state.trim();
        material = material == null ? "" : material;
        mask = mask == null ? VoxelMask.empty() : mask;
        bitCount = mask.count();
        collisionBounds = collisionBounds == null ? new double[6] : collisionBounds.clone();
        flags = flags == null || flags.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(flags));
        backendCost = Math.max(0, backendCost);
    }

    /** True when the state can carry collision without being seen. */
    public boolean invisibleCapable() {
        return invisibleInPack || flags.contains(ShapeFlag.NATURALLY_INVISIBLE);
    }

    /** True when the state has no collision but can still be interacted with. */
    public boolean passable() {
        return !hasCollision;
    }

    public boolean noCollision() {
        return !hasCollision || bitCount == 0;
    }

    /** Top surface height in block units. */
    public double topSurface() {
        return mask.topSurface();
    }

    /** Missing volume relative to {@code target}, in sub-voxels. */
    public int missingBits(VoxelMask target) {
        return Math.max(0, target.count() - mask.intersectionCount(target));
    }

    /** Extra volume compared to {@code target}, in sub-voxels. */
    public int extraBits(VoxelMask target) {
        return Math.max(0, mask.count() - mask.intersectionCount(target));
    }

    /** Lower-case material key used by config and the pool. */
    public String materialKey() {
        return material.toLowerCase(Locale.ROOT);
    }

    /** Same entry with a different state string (used when re-labelling). */
    public ShapeEntry withState(String newState) {
        return new ShapeEntry(newState, material, mask.copy(), bitCount, collisionBounds,
                hasCollision, hasOutline, fullCube, flags, visibleByDefault, invisibleInPack,
                backendCost);
    }

    /** Deterministic order: state string, then material. */
    @Override
    public int compareTo(ShapeEntry other) {
        int byState = state.compareTo(other.state);
        return byState != 0 ? byState : material.compareTo(other.material);
    }

    @Override
    public String toString() {
        return state + " (" + bitCount + " cells, cost " + backendCost + ")";
    }
}
