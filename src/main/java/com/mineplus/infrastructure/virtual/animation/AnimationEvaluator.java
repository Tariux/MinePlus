package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.virtual.VirtualModel;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Samples keyframe tracks and composes per-bone model-space delta matrices.
 *
 * <p>Clip values are <i>deltas relative to the bone's rest pose</i>; multiple
 * concurrent clips compose additively per channel (rotation and position sum,
 * scale multiplies — Bedrock/Blockbench blending semantics), and the composed
 * local deltas propagate down the hierarchy:
 *
 * <pre>world[bone] = world[parent] · T(p/16)·R(Δr)·S(Δs)·T(-p/16)·T(Δt/16)</pre>
 *
 * <p>The rotation quaternion uses the same Blockbench Euler order as the
 * importer's rest baking (Rz·Ry·Rx, extrinsic X-then-Y-then-Z) so animated
 * frames and static frames share one convention.
 */
public final class AnimationEvaluator {

    /** Additive per-bone channel accumulator; scale starts at rest (1,1,1). */
    public static final class BoneDelta {
        public final Vector3f rotationDegrees = new Vector3f();
        public final Vector3f positionPixels = new Vector3f();
        public final Vector3f scale = new Vector3f(1.0f, 1.0f, 1.0f);
        public boolean animated;

        public void reset() {
            rotationDegrees.set(0.0f, 0.0f, 0.0f);
            positionPixels.set(0.0f, 0.0f, 0.0f);
            scale.set(1.0f, 1.0f, 1.0f);
            animated = false;
        }
    }

    private AnimationEvaluator() {
    }

    /** Samples one bone animator of a clip at {@code time} into {@code delta}. */
    public static void accumulate(AnimationClip.BoneAnimation animation, float time, BoneDelta delta) {
        if (!animation.rotation().isEmpty()) {
            float[] v = sample(animation.rotation(), time);
            delta.rotationDegrees.add(v[0], v[1], v[2]);
        }
        if (!animation.position().isEmpty()) {
            float[] v = sample(animation.position(), time);
            delta.positionPixels.add(v[0], v[1], v[2]);
        }
        if (!animation.scale().isEmpty()) {
            float[] v = sample(animation.scale(), time);
            delta.scale.mul(v[0], v[1], v[2]);
        }
        delta.animated = true;
    }

    /** Writes {@code out[i]} = composed world delta of bone {@code i}. */
    public static void composePose(VirtualModel model, BoneDelta[] deltas, Matrix4f[] out) {
        List<VirtualBone> bones = model.bones();
        for (int i = 0; i < bones.size(); i++) {
            VirtualBone bone = bones.get(i);
            Matrix4f parent = bone.isRoot() ? null : out[bone.parentIndex()];
            if (parent == null) {
                out[i] = localDelta(bone, deltas[i]);
            } else {
                out[i] = new Matrix4f(parent).mul(localDelta(bone, deltas[i]));
            }
        }
    }

    private static Matrix4f localDelta(VirtualBone bone, BoneDelta delta) {
        if (delta == null || !delta.animated) {
            return new Matrix4f();
        }
        Vector3f rotation = delta.rotationDegrees;
        Vector3f position = delta.positionPixels;
        Vector3f scale = delta.scale;
        boolean noRotation = rotation.lengthSquared() < 1.0e-8f;
        boolean noPosition = position.lengthSquared() < 1.0e-10f;
        boolean noScale = Math.abs(scale.x - 1.0f) < 1.0e-6f
                && Math.abs(scale.y - 1.0f) < 1.0e-6f
                && Math.abs(scale.z - 1.0f) < 1.0e-6f;
        if (noRotation && noPosition && noScale) {
            return new Matrix4f();
        }

        Vector3f pivot = bone.pivot();
        Quaternionf quaternion = noRotation ? new Quaternionf() : new Quaternionf()
                .rotateZ((float) Math.toRadians(rotation.z))
                .rotateY((float) Math.toRadians(rotation.y))
                .rotateX((float) Math.toRadians(rotation.x));

        // T(p)·R·S·T(-p)·T(Δt): scale and rotate about the pivot, then translate.
        Matrix4f local = new Matrix4f()
                .translate(pivot.x / 16.0f, pivot.y / 16.0f, pivot.z / 16.0f)
                .rotate(quaternion)
                .scale(noScale ? new Vector3f(1.0f, 1.0f, 1.0f) : scale)
                .translate(-pivot.x / 16.0f, -pivot.y / 16.0f, -pivot.z / 16.0f);
        if (!noPosition) {
            local.translate(position.x / 16.0f, position.y / 16.0f, position.z / 16.0f);
        }
        return local;
    }

    /** Samples a sorted keyframe track: clamp outside the range, lerp or step inside. */
    private static float[] sample(List<Keyframe> keyframes, float time) {
        Keyframe first = keyframes.get(0);
        if (time <= first.time()) {
            return valueOf(first);
        }
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (time >= last.time()) {
            return valueOf(last);
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe current = keyframes.get(i);
            Keyframe next = keyframes.get(i + 1);
            if (time >= current.time() && time <= next.time()) {
                if (!next.interpolation().smooth()) {
                    return valueOf(current);
                }
                float span = next.time() - current.time();
                float alpha = span <= 1.0e-6f ? 0.0f : (time - current.time()) / span;
                return new float[]{
                        lerp(current.x(), next.x(), alpha),
                        lerp(current.y(), next.y(), alpha),
                        lerp(current.z(), next.z(), alpha)
                };
            }
        }
        return valueOf(last);
    }

    private static float[] valueOf(Keyframe keyframe) {
        return new float[]{keyframe.x(), keyframe.y(), keyframe.z()};
    }

    private static float lerp(float a, float b, float alpha) {
        return a + (b - a) * alpha;
    }
}
