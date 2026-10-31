package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.virtual.VirtualModel;
import java.util.Arrays;
import java.util.Map;
import org.joml.Matrix4f;

/**
 * Bone-level priority resolution and crossfade composition.
 *
 * <p>For every bone only the highest-priority level animating it contributes;
 * controllers sharing a priority compose additively (the historical behavior,
 * so a model with no priorities evaluates exactly as before). While a winner is
 * fading in or out, the next lower level is composed as a second pose and the
 * two bone matrices are lerped, which is what blends a reload over a walk and
 * back into the walk's own phase.</p>
 *
 * <p>All working memory lives in {@link ThreadLocal} scratch so steady-state
 * ticks allocate nothing.</p>
 */
final class PoseSolver {

    private static final int INITIAL_BONES = 256;

    private enum Layer { ALL, TOP, LOWER }

    private static final class Scratch {
        AnimationEvaluator.BoneDelta[] top = new AnimationEvaluator.BoneDelta[INITIAL_BONES];
        AnimationEvaluator.BoneDelta[] lower = new AnimationEvaluator.BoneDelta[INITIAL_BONES];
        Matrix4f[] lowerPose = new Matrix4f[INITIAL_BONES];
        int[] topPriority = new int[INITIAL_BONES];
        int[] nextPriority = new int[INITIAL_BONES];
        float[] alpha = new float[INITIAL_BONES];

        void ensure(int bones) {
            if (bones <= top.length) {
                return;
            }
            int size = Math.max(bones, top.length * 2);
            top = Arrays.copyOf(top, size);
            lower = Arrays.copyOf(lower, size);
            lowerPose = Arrays.copyOf(lowerPose, size);
            topPriority = Arrays.copyOf(topPriority, size);
            nextPriority = Arrays.copyOf(nextPriority, size);
            alpha = Arrays.copyOf(alpha, size);
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private PoseSolver() {
    }

    /** Composes the resolved pose of {@code samples[0..count)} into {@code out[0..boneCount)}. */
    static void compose(VirtualModel model, ControllerSample[] samples, int count, Matrix4f[] out) {
        int boneCount = Math.max(1, model.bones().size());
        if (count == 0) {
            for (int i = 0; i < boneCount; i++) {
                out[i].identity();
            }
            return;
        }

        // Fast path: one priority level and no fades is exactly the additive
        // pre-priority evaluation (identical matrices, one compose pass).
        boolean uniform = samples[0].weight() >= 1.0f;
        if (uniform) {
            int priority = samples[0].priority();
            for (int i = 1; i < count; i++) {
                if (samples[i].weight() < 1.0f || samples[i].priority() != priority) {
                    uniform = false;
                    break;
                }
            }
        }

        Scratch scratch = SCRATCH.get();
        scratch.ensure(boneCount);
        if (uniform) {
            accumulate(model, samples, count, scratch.top, boneCount, Layer.ALL);
            AnimationEvaluator.composePose(model, scratch.top, out);
            return;
        }

        int[] topPriority = scratch.topPriority;
        int[] nextPriority = scratch.nextPriority;
        float[] alpha = scratch.alpha;
        Arrays.fill(topPriority, 0, boneCount, Integer.MIN_VALUE);
        Arrays.fill(nextPriority, 0, boneCount, Integer.MIN_VALUE);
        Arrays.fill(alpha, 0, boneCount, 0.0f);

        for (int i = 0; i < count; i++) {
            ControllerSample sample = samples[i];
            int priority = sample.priority();
            for (Map.Entry<String, AnimationClip.BoneAnimation> entry : sample.clip.animators().entrySet()) {
                int bone = model.boneIndex(entry.getKey());
                if (bone < 0 || bone >= boneCount || !sample.boneEnabled(bone)) {
                    continue;
                }
                if (priority > topPriority[bone]) {
                    nextPriority[bone] = topPriority[bone];
                    topPriority[bone] = priority;
                } else if (priority < topPriority[bone] && priority > nextPriority[bone]) {
                    nextPriority[bone] = priority;
                }
            }
        }

        // Level alpha is the strongest weight at the winning priority, so a
        // single fading winner pulls its whole level down and the next level in.
        boolean anyBlend = false;
        for (int i = 0; i < count; i++) {
            ControllerSample sample = samples[i];
            int priority = sample.priority();
            for (Map.Entry<String, AnimationClip.BoneAnimation> entry : sample.clip.animators().entrySet()) {
                int bone = model.boneIndex(entry.getKey());
                if (bone < 0 || bone >= boneCount || priority != topPriority[bone] || !sample.boneEnabled(bone)) {
                    continue;
                }
                if (sample.weight() > alpha[bone]) {
                    alpha[bone] = sample.weight();
                }
            }
        }
        for (int bone = 0; bone < boneCount; bone++) {
            if (topPriority[bone] != Integer.MIN_VALUE && alpha[bone] < 1.0f) {
                anyBlend = true;
                break;
            }
        }

        accumulate(model, samples, count, scratch.top, boneCount, Layer.TOP);
        AnimationEvaluator.composePose(model, scratch.top, out);
        if (!anyBlend) {
            return;
        }
        accumulate(model, samples, count, scratch.lower, boneCount, Layer.LOWER);
        AnimationEvaluator.composePose(model, scratch.lower, scratch.lowerPose);
        for (int bone = 0; bone < boneCount; bone++) {
            if (topPriority[bone] != Integer.MIN_VALUE && alpha[bone] < 1.0f) {
                out[bone].lerp(scratch.lowerPose[bone], 1.0f - alpha[bone]);
            }
        }
    }

    private static void accumulate(VirtualModel model, ControllerSample[] samples, int count,
                                   AnimationEvaluator.BoneDelta[] deltas, int boneCount, Layer layer) {
        Scratch scratch = SCRATCH.get();
        for (int i = 0; i < boneCount; i++) {
            AnimationEvaluator.BoneDelta delta = deltas[i];
            if (delta == null) {
                deltas[i] = delta = new AnimationEvaluator.BoneDelta();
            } else {
                delta.reset();
            }
        }
        for (int i = 0; i < count; i++) {
            ControllerSample sample = samples[i];
            float weight = sample.weight();
            for (Map.Entry<String, AnimationClip.BoneAnimation> entry : sample.clip.animators().entrySet()) {
                int bone = model.boneIndex(entry.getKey());
                if (bone < 0 || bone >= boneCount || !sample.boneEnabled(bone)) {
                    continue;
                }
                switch (layer) {
                    case TOP -> {
                        if (sample.priority() != scratch.topPriority[bone]) {
                            continue;
                        }
                    }
                    case LOWER -> {
                        if (sample.priority() != scratch.nextPriority[bone]) {
                            continue;
                        }
                    }
                    default -> {
                    }
                }
                AnimationEvaluator.accumulate(entry.getValue(), sample.time, deltas[bone], weight);
            }
        }
    }
}
