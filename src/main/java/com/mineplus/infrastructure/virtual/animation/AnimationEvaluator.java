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
 * frames and static frames share one convention.</p>
 *
 * <p>The pose loop runs every tick for every animated instance, so all working
 * memory (sampled channel values, the local bone delta, its rotation quaternion)
 * lives in {@link ThreadLocal} scratch and steady-state ticks allocate nothing.</p>
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

    private static final ThreadLocal<float[]> SCRATCH_VALUES = ThreadLocal.withInitial(() -> new float[3]);
    private static final ThreadLocal<Matrix4f> SCRATCH_LOCAL = ThreadLocal.withInitial(Matrix4f::new);
    private static final ThreadLocal<Quaternionf> SCRATCH_QUATERNION = ThreadLocal.withInitial(Quaternionf::new);

    private AnimationEvaluator() {
    }

    /** Samples one bone animator of a clip at {@code time} into {@code delta}. */
    public static void accumulate(AnimationClip.BoneAnimation animation, float time, BoneDelta delta) {
        float[] values = SCRATCH_VALUES.get();
        if (!animation.rotation().isEmpty()) {
            sample(animation.rotation(), time, values);
            delta.rotationDegrees.add(values[0], values[1], values[2]);
        }
        if (!animation.position().isEmpty()) {
            sample(animation.position(), time, values);
            delta.positionPixels.add(values[0], values[1], values[2]);
        }
        if (!animation.scale().isEmpty()) {
            sample(animation.scale(), time, values);
            delta.scale.mul(values[0], values[1], values[2]);
        }
        delta.animated = true;
    }

    /**
     * Writes {@code out[i]} = composed world delta of bone {@code i}. Bones are in
     * preorder (parents precede children), so one forward pass suffices; results
     * are written in place into {@code out} without allocation.
     */
    public static void composePose(VirtualModel model, BoneDelta[] deltas, Matrix4f[] out) {
        List<VirtualBone> bones = model.bones();
        Matrix4f local = SCRATCH_LOCAL.get();
        for (int i = 0; i < bones.size(); i++) {
            VirtualBone bone = bones.get(i);
            localDelta(bone, deltas[i], local);
            if (bone.isRoot()) {
                out[i].set(local);
            } else {
                out[i].set(out[bone.parentIndex()]).mul(local);
            }
        }
    }

    /** Writes the bone's local delta {@code T(p)·R·S·T(-p)·T(Δt)} into {@code out}. */
    private static void localDelta(VirtualBone bone, BoneDelta delta, Matrix4f out) {
        out.identity();
        if (delta == null || !delta.animated) {
            return;
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
            return;
        }

        Vector3f pivot = bone.pivot();
        out.translation(pivot.x / 16.0f, pivot.y / 16.0f, pivot.z / 16.0f);
        if (!noRotation) {
            // Blockbench Euler order Rz·Ry·Rx (extrinsic X-then-Y-then-Z), matching
            // the importer's rest baking. Chained rotateZ/rotateY/rotateX on a
            // fresh identity quaternion composes exactly that product.
            out.rotate(SCRATCH_QUATERNION.get()
                    .identity()
                    .rotateZ((float) Math.toRadians(rotation.z))
                    .rotateY((float) Math.toRadians(rotation.y))
                    .rotateX((float) Math.toRadians(rotation.x)));
        }
        // Rest scale is (1,1,1), so scaling is a no-op for unanimated scale channels.
        out.scale(scale);
        out.translate(-pivot.x / 16.0f, -pivot.y / 16.0f, -pivot.z / 16.0f);
        if (!noPosition) {
            out.translate(position.x / 16.0f, position.y / 16.0f, position.z / 16.0f);
        }
    }

    /**
     * Samples a sorted keyframe track into {@code out[0..2]}: clamp outside the
     * range, lerp or step inside. No allocation.
     */
    private static void sample(List<Keyframe> keyframes, float time, float[] out) {
        Keyframe first = keyframes.get(0);
        if (time <= first.time()) {
            valueOf(first, out);
            return;
        }
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (time >= last.time()) {
            valueOf(last, out);
            return;
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe current = keyframes.get(i);
            Keyframe next = keyframes.get(i + 1);
            if (time >= current.time() && time <= next.time()) {
                if (!next.interpolation().smooth()) {
                    valueOf(current, out);
                    return;
                }
                float span = next.time() - current.time();
                float alpha = span <= 1.0e-6f ? 0.0f : (time - current.time()) / span;
                out[0] = lerp(current.x(), next.x(), alpha);
                out[1] = lerp(current.y(), next.y(), alpha);
                out[2] = lerp(current.z(), next.z(), alpha);
                return;
            }
        }
        valueOf(last, out);
    }

    private static void valueOf(Keyframe keyframe, float[] out) {
        out[0] = keyframe.x();
        out[1] = keyframe.y();
        out[2] = keyframe.z();
    }

    private static float lerp(float a, float b, float alpha) {
        return a + (b - a) * alpha;
    }
}
