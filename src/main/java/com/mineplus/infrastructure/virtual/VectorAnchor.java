package com.mineplus.infrastructure.virtual;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A named point in model space, derived from Blockbench {@code locator} /
 * {@code null_object} elements. Anchors are transformed by the same parent
 * outliner chain as cubes, so they track the model's orientation without any
 * per-tick recomputation. Use them for mount seats, signal emit points, or any
 * vector hook that must follow the model.
 */
public record VectorAnchor(String name, Vector3f offset, Quaternionf rotation) {

    public VectorAnchor {
        offset = offset == null ? new Vector3f() : offset;
        rotation = rotation == null ? new Quaternionf() : rotation;
    }
}
