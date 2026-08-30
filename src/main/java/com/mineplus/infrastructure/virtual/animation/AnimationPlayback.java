package com.mineplus.infrastructure.virtual.animation;

/** Playback options for {@code AnimationApi.play}. */
public record AnimationPlayback(
        float speed,
        LoopMode loopOverride,
        float startTime
) {

    public AnimationPlayback {
        speed = speed <= 0.0f ? 1.0f : speed;
        startTime = Math.max(0.0f, startTime);
    }

    public static AnimationPlayback defaults() {
        return new AnimationPlayback(1.0f, null, 0.0f);
    }

    public static AnimationPlayback speed(float speed) {
        return new AnimationPlayback(speed, null, 0.0f);
    }
}
