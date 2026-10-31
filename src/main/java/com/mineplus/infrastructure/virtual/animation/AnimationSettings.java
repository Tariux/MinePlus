package com.mineplus.infrastructure.virtual.animation;

/**
 * Global animation settings (the {@code ANIMATION} section of
 * {@code settings.mp.yml}).
 *
 * <p>Update strategy: the server pushes target matrices every
 * {@code tickIntervalTicks} and the vanilla client interpolates display
 * transformations between pushes for {@code interpolationTicks} — the rendered
 * motion then advances at the client's own frame rate, which is the maximum a
 * purely server-side renderer can achieve (the server cannot emit packets
 * faster than its own tick loop).
 */
public record AnimationSettings(
        boolean enabled,
        int tickIntervalTicks,
        int interpolationTicks,
        boolean autoplay,
        PosePipeline pipeline,
        int maxApplyPerTick,
        boolean autoState,
        TransitionMode transition,
        int transitionTicks
) {

    public AnimationSettings {
        tickIntervalTicks = Math.max(1, tickIntervalTicks);
        interpolationTicks = Math.max(0, interpolationTicks);
        pipeline = pipeline == null ? PosePipeline.ASYNC_COMPUTE : pipeline;
        maxApplyPerTick = Math.max(1, maxApplyPerTick);
        transition = transition == null ? TransitionMode.AFTER_CURRENT : transition;
        transitionTicks = Math.max(0, transitionTicks);
    }

    /** Keeps the historical four-argument shape working (async compute, 512 applies, no auto state). */
    public AnimationSettings(boolean enabled, int tickIntervalTicks, int interpolationTicks, boolean autoplay) {
        this(enabled, tickIntervalTicks, interpolationTicks, autoplay, PosePipeline.ASYNC_COMPUTE, 512,
                false, TransitionMode.AFTER_CURRENT, 5);
    }

    /** Keeps the six-argument shape of the pose-pipeline release working. */
    public AnimationSettings(boolean enabled, int tickIntervalTicks, int interpolationTicks, boolean autoplay,
                             PosePipeline pipeline, int maxApplyPerTick) {
        this(enabled, tickIntervalTicks, interpolationTicks, autoplay, pipeline, maxApplyPerTick,
                false, TransitionMode.AFTER_CURRENT, 5);
    }

    public static AnimationSettings defaults() {
        return new AnimationSettings(true, 1, 1, true, PosePipeline.ASYNC_COMPUTE, 512,
                false, TransitionMode.AFTER_CURRENT, 5);
    }

    /** Client interpolation window; {@code 0} means "match the tick interval". */
    public int effectiveInterpolationTicks() {
        return interpolationTicks > 0 ? interpolationTicks : tickIntervalTicks;
    }
}
