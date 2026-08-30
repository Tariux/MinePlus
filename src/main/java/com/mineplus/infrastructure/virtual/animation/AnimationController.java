package com.mineplus.infrastructure.virtual.animation;

import java.util.HashSet;
import java.util.Set;

/**
 * Playback state of one clip on one rendered instance. Bone indices in
 * {@code disabledBones} contribute identity for this controller while their
 * parent deltas still propagate through the hierarchy.
 */
final class AnimationController {

    private final AnimationClip clip;
    private float speed = 1.0f;
    private LoopMode loopOverride;
    private float time;
    private boolean playing = true;
    private boolean completedFired;
    private final Set<Integer> disabledBones = new HashSet<>();

    AnimationController(AnimationClip clip) {
        this.clip = clip;
    }

    AnimationClip clip() {
        return clip;
    }

    float speed() {
        return speed;
    }

    void setSpeed(float speed) {
        this.speed = speed <= 0.0f ? 1.0f : speed;
    }

    LoopMode effectiveLoop() {
        return loopOverride != null ? loopOverride : clip.loop();
    }

    void setLoopOverride(LoopMode loopOverride) {
        this.loopOverride = loopOverride;
    }

    float time() {
        return time;
    }

    void setTime(float time) {
        this.time = Math.max(0.0f, time);
        this.completedFired = false;
    }

    boolean playing() {
        return playing;
    }

    void setPlaying(boolean playing) {
        this.playing = playing;
    }

    boolean isBoneEnabled(int boneIndex) {
        return !disabledBones.contains(boneIndex);
    }

    void setBoneEnabled(int boneIndex, boolean enabled) {
        if (enabled) {
            disabledBones.remove(boneIndex);
        } else {
            disabledBones.add(boneIndex);
        }
    }

    boolean hasDisabledBones() {
        return !disabledBones.isEmpty();
    }

    /**
     * Advances {@code deltaTime} seconds; reports {@code true} once when the
     * clip reaches its end. LOOP wraps time; ONCE/HOLD clamp to the end frame
     * and auto-pause (the manager removes ONCE controllers, HOLD controllers
     * stay frozen on the final frame).
     */
    boolean advance(float deltaTime) {
        time += deltaTime;
        float length = Math.max(clip.length(), 1.0e-4f);
        if (time < length) {
            return false;
        }
        if (effectiveLoop() == LoopMode.LOOP) {
            time %= length;
            return false;
        }
        time = length;
        if (completedFired) {
            playing = false;
            return false;
        }
        completedFired = true;
        playing = false;
        return true;
    }

    AnimationState snapshot() {
        return new AnimationState(
                clip.name(),
                playing,
                !playing,
                time,
                clip.length(),
                effectiveLoop(),
                speed
        );
    }
}
