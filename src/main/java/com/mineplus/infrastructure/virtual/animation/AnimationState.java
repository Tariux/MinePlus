package com.mineplus.infrastructure.virtual.animation;

/** Read-only playback snapshot returned by {@code AnimationApi.state}. */
public record AnimationState(
        String animation,
        boolean playing,
        boolean paused,
        float time,
        float length,
        LoopMode loop,
        float speed
) {

    public float progress() {
        return length <= 0.0f ? 0.0f : Math.min(1.0f, time / length);
    }
}
