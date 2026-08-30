package com.mineplus.infrastructure.virtual.animation;

import java.util.List;
import org.joml.Vector3f;

/**
 * One outliner group promoted to an animatable bone.
 *
 * <p>Bones are listed in <b>preorder</b> (every parent precedes its children),
 * which lets the evaluator compose world deltas in a single forward pass.
 * {@code pivot} is the group's Blockbench {@code origin} in model pixels.
 *
 * <p>Rest transforms are intentionally not stored: cubes bake their full rest
 * matrix at import, and clips carry deltas relative to rest, so the runtime only
 * ever needs the hierarchy, the pivots, and the per-bone deltas. The delta
 * conjugation uses absolute pivots, which is exact when groups have identity
 * rest rotations (the common case; both reference models qualify) and a close
 * visual approximation otherwise.
 */
public record VirtualBone(
        String name,
        String uuid,
        int parentIndex,
        Vector3f pivot,
        List<Integer> childIndices
) {

    public VirtualBone {
        name = name == null || name.isBlank() ? "bone" : name;
        pivot = new Vector3f(pivot == null ? new Vector3f() : pivot);
        childIndices = childIndices == null ? List.of() : List.copyOf(childIndices);
    }

    public boolean isRoot() {
        return parentIndex < 0;
    }
}
