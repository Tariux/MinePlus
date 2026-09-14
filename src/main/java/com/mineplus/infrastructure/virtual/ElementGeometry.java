package com.mineplus.infrastructure.virtual;

import org.joml.Vector3f;

/**
 * The raw, pre-bake geometry of one Blockbench cube element, preserved so the
 * pack writer can reproduce authored rotations exactly instead of degrading a
 * rotated cube to its axis-aligned bounding box.
 *
 * <p>Coordinates are Blockbench texture/model pixels (a full block spans
 * {@code [0..16]} in {@code java_block} space), matching the bbmodel's
 * {@code from}/{@code to}/{@code origin}. {@code rotation} is the authored
 * Euler rotation in degrees; Blockbench's {@code java_block} exporter emits
 * single-axis rotations only, which is exactly what vanilla element models
 * support, so the writer can reproduce it natively.
 *
 * <p>Only populated when the element's enclosing outliner group baked to the
 * identity transform (no parent rotation/pivot); a transformed parent would
 * make raw coordinates unusable, and the writer falls back to the baked box.
 *
 * @param from        minimum corner in pixels (inflate already applied)
 * @param to          maximum corner in pixels (inflate already applied)
 * @param origin      rotation pivot in pixels
 * @param rotation    authored Euler rotation in degrees
 * @param rescale     Blockbench's {@code rescale} flag for the rotation
 */
public record ElementGeometry(
        Vector3f from,
        Vector3f to,
        Vector3f origin,
        Vector3f rotation,
        boolean rescale
) {

    public ElementGeometry {
        from = from == null ? new Vector3f() : new Vector3f(from);
        to = to == null ? new Vector3f() : new Vector3f(to);
        origin = origin == null ? new Vector3f() : new Vector3f(origin);
        rotation = rotation == null ? new Vector3f() : new Vector3f(rotation);
    }

    /** True when the authored rotation is non-zero on at least one axis. */
    public boolean hasRotation() {
        return Math.abs(rotation.x) > 1.0e-4f
                || Math.abs(rotation.y) > 1.0e-4f
                || Math.abs(rotation.z) > 1.0e-4f;
    }
}
