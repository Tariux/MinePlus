package com.mineplus.infrastructure.virtual.animation;

import java.util.HashSet;
import java.util.Set;

/**
 * Playback state of one clip on one rendered instance. Bone indices in
 * {@code disabledBones} contribute identity for this controller while their
 * parent deltas still propagate through the hierarchy.
 *
 * <p>{@code priority} resolves bone-level winners: only the highest-priority
 * level animating a bone contributes, while equal priorities compose additively
 * (the historical behavior). {@code fadeInTicks}/{@code fadeOutTicks} scale the
 * controller's weight so a higher layer blends in and back out; both default to
 * zero, which is the immediate (pre-priority) behavior.</p>
 */
final class AnimationController {

    private final AnimationClip clip;
    private float speed = 1.0f;
    private LoopMode loopOverride;
    private float time;
    private boolean playing = true;
    private boolean completedFired;
    private final Set<Integer> disabledBones = new HashSet<>();
    private int priority;
    private int fadeInTicks;
    private int fadeOutTicks;
    /** Ticks elapsed into the current fade; also used for the fade-in clock. */
    private float fadeElapsed;
    private float fadeInElapsed;
    private boolean stopping;
    private boolean automatic;
    private boolean wrapped;

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

    int priority() {
        return priority;
    }

    void setPriority(int priority) {
        this.priority = priority;
    }

    int fadeInTicks() {
        return fadeInTicks;
    }

    int fadeOutTicks() {
        return fadeOutTicks;
    }

    void setFades(int fadeInTicks, int fadeOutTicks) {
        this.fadeInTicks = Math.max(0, fadeInTicks);
        this.fadeOutTicks = Math.max(0, fadeOutTicks);
    }

    /** {@code true} for controllers started by automatic state selection. */
    boolean automatic() {
        return automatic;
    }

    void setAutomatic(boolean automatic) {
        this.automatic = automatic;
    }

    /** Marks the controller for fade-out; it stops advancing and is removed once weight reaches 0. */
    void beginStop() {
        if (fadeOutTicks <= 0) {
            return;
        }
        // Start the fade from the current weight so a mid-fade-in controller
        // blends out continuously instead of snapping to full weight first.
        float current = weight();
        stopping = true;
        playing = false;
        fadeElapsed = (1.0f - current) * fadeOutTicks;
    }

    boolean stopping() {
        return stopping;
    }

    /** {@code true} once a stopping controller has faded out completely. */
    boolean fadeComplete() {
        return stopping && weight() <= 0.0f;
    }

    /**
     * Current blend weight in {@code [0,1]}: rises over {@code fadeInTicks},
     * falls over {@code fadeOutTicks} after {@link #beginStop()}.
     */
    float weight() {
        if (stopping) {
            if (fadeOutTicks <= 0) {
                return 0.0f;
            }
            return 1.0f - Math.min(1.0f, fadeElapsed / fadeOutTicks);
        }
        if (fadeInTicks <= 0) {
            return 1.0f;
        }
        float in = Math.min(1.0f, fadeInElapsed / fadeInTicks);
        return in;
    }

    /** Advances fade clocks by {@code deltaTicks}; reports whether a weight change is pending. */
    void advanceFade(float deltaTicks) {
        if (stopping) {
            fadeElapsed += deltaTicks;
        } else if (fadeInTicks > 0 && fadeInElapsed < fadeInTicks) {
            fadeInElapsed = Math.min(fadeInTicks, fadeInElapsed + deltaTicks);
        }
    }

    /** {@code true} when the loop wrapped during the last {@link #advance(float)}. */
    boolean wrapped() {
        return wrapped;
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

    /** Sorted copy for the async pose worker; {@code null} when every bone is enabled. */
    int[] disabledBonesSnapshot() {
        if (disabledBones.isEmpty()) {
            return null;
        }
        int[] snapshot = new int[disabledBones.size()];
        int i = 0;
        for (Integer bone : disabledBones) {
            snapshot[i++] = bone;
        }
        java.util.Arrays.sort(snapshot);
        return snapshot;
    }

    /**
     * Advances {@code deltaTime} seconds; reports {@code true} once when the
     * clip reaches its end. LOOP wraps time; ONCE/HOLD clamp to the end frame
     * and auto-pause (the manager removes ONCE controllers, HOLD controllers
     * stay frozen on the final frame).
     */
    boolean advance(float deltaTime) {
        wrapped = false;
        time += deltaTime;
        float length = Math.max(clip.length(), 1.0e-4f);
        if (time < length) {
            return false;
        }
        if (effectiveLoop() == LoopMode.LOOP) {
            time %= length;
            wrapped = true;
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
