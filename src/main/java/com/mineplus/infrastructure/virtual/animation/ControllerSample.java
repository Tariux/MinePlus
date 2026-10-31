package com.mineplus.infrastructure.virtual.animation;

/**
 * Mutable snapshot of one controller for pose evaluation. Instances are reused
 * per rendered model: the main thread rewrites the fields between frames and
 * the pose worker only reads them while a batch is in flight (the manager never
 * resubmits while an evaluation is running, so the buffer cannot be rewritten
 * under the worker).
 */
final class ControllerSample {

    AnimationClip clip;
    float time;
    /** Sorted disabled bone indices, or {@code null} when every bone is enabled. */
    int[] disabledBones;
    int priority;
    float weight;

    void set(AnimationController controller) {
        this.clip = controller.clip();
        this.time = controller.time();
        this.disabledBones = controller.hasDisabledBones() ? controller.disabledBonesSnapshot() : null;
        this.priority = controller.priority();
        this.weight = controller.weight();
    }

    AnimationClip clip() {
        return clip;
    }

    float time() {
        return time;
    }

    int priority() {
        return priority;
    }

    float weight() {
        return weight;
    }

    boolean boneEnabled(int boneIndex) {
        return disabledBones == null || java.util.Arrays.binarySearch(disabledBones, boneIndex) < 0;
    }
}
