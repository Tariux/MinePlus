package com.mineplus.infrastructure.mob.custom;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The one coordinate convention every custom-mob rig obeys, kept in one place.
 *
 * <p>Two spaces meet inside a mob rig. <b>Authored</b> space is Blockbench's:
 * part pivots are vanilla pixels growing <em>downward</em> from the model's
 * ground line (kept that way because an imported outliner names absolute
 * origins in it), a {@link MobPart} rest rotation is stored as vanilla
 * <em>y-down</em> degrees, and an animation curve of
 * {@link MobAnimation} is authored in <em>y-up</em> degrees. <b>Rig</b> space is
 * the client's: y-up, one unit per pixel, a bone's display rotated by
 * {@code Rz·Ry·Rx} of its own y-up degrees.</p>
 *
 * <p>The flip rule: a y-down rotation becomes y-up by negating the two axes that
 * tilt around the flipped vertical ({@code X} and {@code Z}); the yaw about
 * {@code Y} keeps its sign. A rest and a clip delta live in the <em>same</em>
 * y-up triple and are additive there — Blockbench semantics, since an animation
 * stores offsets from the model's rest pose — so they are summed before a
 * quaternion is built. Composing them as two quaternions instead would apply
 * the rest to the bone's own display only, leaving every bone below a rotated
 * group uncarried.</p>
 */
final class MobPoseSpace {

    private MobPoseSpace() {
    }

    /** The part's authored rest rotation, in y-up degrees. */
    static void restDegrees(MobPart part, Vector3f out) {
        out.set(-part.rotationX(), part.rotationY(), -part.rotationZ());
    }

    /** The part's authored rest rotation as a rig-space quaternion. */
    static Quaternionf restRotation(MobPart part) {
        return euler(new Quaternionf(), -part.rotationX(), part.rotationY(), -part.rotationZ());
    }

    /** {@code R_z·R_y·R_x} of y-up degrees — the authored marker's bone order. */
    static Quaternionf euler(Quaternionf out, float xDegrees, float yDegrees, float zDegrees) {
        return out.rotationZ((float) Math.toRadians(zDegrees))
                .rotateY((float) Math.toRadians(yDegrees))
                .rotateX((float) Math.toRadians(xDegrees));
    }

    /**
     * The entity's yaw frame: the model's front is {@code -z}, so a body yaw of
     * {@code 0} (south) must turn the model by a half rotation. {@code
     * yawOffsetDegrees} rotates the model once more — the authored correction
     * for a model built facing a different way than the mob walks.
     */
    static Quaternionf yawFrame(Quaternionf out, float entityYaw, float yawOffsetDegrees) {
        return out.rotationY((float) Math.toRadians(180.0 - entityYaw + yawOffsetDegrees));
    }

    /** Shortest-arc interpolation weight, so a crossing of ±180° never spins. */
    static float unwrap(float previous, float current) {
        float delta = current - previous;
        while (delta > 180.0f) {
            delta -= 360.0f;
        }
        while (delta < -180.0f) {
            delta += 360.0f;
        }
        return delta;
    }
}
