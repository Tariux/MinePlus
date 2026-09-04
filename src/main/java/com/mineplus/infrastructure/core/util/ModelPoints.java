package com.mineplus.infrastructure.core.util;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3f;

/**
 * Model-space to world-space math for the CENTER origin convention.
 *
 * <p>Pixel (0,0,0) of a CENTER-anchored model is the anchor block's center at
 * its base; a model point {@code p} (in pixels) sits at
 * {@code anchorCenter + R · (p/16 − (0, 1/2, 0))} where {@code R} is the
 * instance rotation. This is exactly the transform the display renderer and
 * the occupancy calculator apply, so feature geometry (muzzle exits,
 * seats, conveyor mounts) computed through this class can never drift from
 * the rendered model.
 */
public final class ModelPoints {

    public static final float BLOCKS_PER_PIXEL = 1.0f / 16.0f;

    private ModelPoints() {
    }

    /**
     * Computes the world-space offset (relative to the anchor block's center)
     * of a model point given in pixels. Returns a new vector; the inputs are
     * not modified.
     */
    public static Vector3f toWorldOffset(MultiBlockInstance instance, Vector3f pixels) {
        Vector3f offset = new Vector3f(pixels).mul(BLOCKS_PER_PIXEL).sub(0.0f, 0.5f, 0.0f);
        instance.rotation().transform(offset);
        return offset;
    }

    /**
     * Resolves a model point given in pixels to a world location, or
     * {@code null} if the instance's world is not loaded.
     */
    public static Location toWorld(MultiBlockInstance instance, World world, Vector3f pixels) {
        if (world == null) {
            return null;
        }
        Vector3f offset = toWorldOffset(instance, pixels);
        return new Location(
                world,
                instance.coordinate().x() + 0.5D + offset.x,
                instance.coordinate().y() + 0.5D + offset.y,
                instance.coordinate().z() + 0.5D + offset.z
        );
    }

    /**
     * Transforms a model-space direction (e.g. a barrel axis) into world
     * space. Returns a new normalized-free vector; callers normalize as
     * needed.
     */
    public static Vector3f direction(MultiBlockInstance instance, Vector3f modelAxis) {
        Vector3f axis = new Vector3f(modelAxis);
        instance.rotation().transform(axis);
        return axis;
    }
}
