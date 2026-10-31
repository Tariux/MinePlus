package com.mineplus.infrastructure.mob.custom.rig;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The one place blockbench/model-space math lives, with its conventions stated
 * once. Every renderer and emitter builds on these helpers; nothing else may
 * derive a rotation or a pivot offset.
 *
 * <p><b>Convention table.</b></p>
 *
 * <ul>
 *   <li>Model space is Blockbench space: pixels, {@code y} up, the model's
 *       visual front is {@code -z}. One pixel is {@code 1/16} block.</li>
 *   <li>Bone pivots are absolute model pixels; a bone's translation in its
 *       parent frame is {@code (pivot - parentPivot) / 16}.</li>
 *   <li>Rotations are authored Euler degrees applied in the order
 *       {@code x → y → z} (extrinsic, fixed axes) — Blockbench's own order —
 *       which composes as the matrix product {@code Rz · Ry · Rx} for column
 *       vectors. Rest rotation and clip keys are <b>additive per component</b>
 *       before the single Euler conversion.</li>
 *   <li>Because the rig lives in y-up space while vanilla entity part tables
 *       are y-down, a part table's rest angles enter as {@code (-rx, ry, -rz)}
 *       and its y coordinates as {@code heightPx - y} (the legacy adapter does
 *       this; file parsers are already y-up).</li>
 *   <li>The whole rig is placed with a yaw frame of {@code 180° - bodyYaw}
 *       about the model's up axis: at {@code bodyYaw = 0} the rig faces south
 *       (+z) like the entity it covers, and the model's {@code -z} front
 *       points where the entity walks.</li>
 *   <li>Head look adds to the head bone's Euler: model pitch is
 *       {@code -entityPitch} (positive entity pitch is down) and model yaw is
 *       {@code -(headYaw - bodyYaw)} clamped to ±75°, both inside the same
 *       {@code x → y → z} composition, so pitch is applied before yaw.</li>
 *   <li>A bone's local matrix is {@code T(relPivot/16) · R · S}: cubes are
 *       stored pivot-relative, so rotation and scale are already about the
 *       pivot and no {@code T(-pivot)} tail is needed.</li>
 * </ul>
 */
public final class BoneTransformMath {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final float PX_TO_BLOCK = 1.0f / 16.0f;

    private BoneTransformMath() {
    }

    /**
     * Euler degrees ({@code x → y → z} application order) to a quaternion,
     * equivalent to the matrix product {@code Rz · Ry · Rx}.
     */
    public static Quaternionf eulerZyxDegrees(float rxDeg, float ryDeg, float rzDeg, Quaternionf out) {
        out.rotationZ(rzDeg * DEG_TO_RAD);
        out.rotateY(ryDeg * DEG_TO_RAD);
        out.rotateX(rxDeg * DEG_TO_RAD);
        return out;
    }

    /**
     * A bone's local matrix from its pivot delta (model pixels, relative to the
     * parent's pivot), combined Euler degrees (rest + clip + head look) and an
     * animated scale multiplier: {@code T(relPivot/16) · R · S}.
     */
    public static org.joml.Matrix4f localMatrix(float relPivotX, float relPivotY, float relPivotZ,
                                                float rxDeg, float ryDeg, float rzDeg,
                                                float scale, org.joml.Matrix4f out,
                                                Quaternionf scratch) {
        out.translation(relPivotX * PX_TO_BLOCK, relPivotY * PX_TO_BLOCK, relPivotZ * PX_TO_BLOCK);
        eulerZyxDegrees(rxDeg, ryDeg, rzDeg, scratch);
        out.rotate(scratch);
        if (scale != 1.0f) {
            out.scale(scale);
        }
        return out;
    }

    /** Model-space yaw frame for a Bukkit body yaw ({@code 180 - bodyYaw}). */
    public static Quaternionf yawFrame(float bodyYawDegrees, Quaternionf out) {
        return out.rotationY((180.0f - bodyYawDegrees) * DEG_TO_RAD);
    }

    /** Clamps a Bukkit yaw delta into the head bone's model yaw (±75°). */
    public static float headYawModel(float headYawDegrees, float bodyYawDegrees) {
        float delta = wrapDegrees(headYawDegrees - bodyYawDegrees);
        return -clamp(delta, -75.0f, 75.0f);
    }

    /** A Bukkit pitch (positive down) as the head bone's model pitch. */
    public static float headPitchModel(float pitchDegrees) {
        return -clamp(wrapDegrees(pitchDegrees), -75.0f, 75.0f);
    }

    /** Wraps an angle into {@code (-180, 180]}. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped <= -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Extracts a bone's world translation from its composed matrix into {@code out}. */
    public static Vector3f translationOf(org.joml.Matrix4fc matrix, Vector3f out) {
        return matrix.getTranslation(out);
    }
}
