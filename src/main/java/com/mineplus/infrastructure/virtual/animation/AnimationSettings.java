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
        boolean autoplay
) {

    public AnimationSettings {
        tickIntervalTicks = Math.max(1, tickIntervalTicks);
        interpolationTicks = Math.max(0, interpolationTicks);
    }

    public static AnimationSettings defaults() {
        return new AnimationSettings(true, 1, 1, true);
    }

    /** Client interpolation window; {@code 0} means "match the tick interval". */
    public int effectiveInterpolationTicks() {
        return interpolationTicks > 0 ? interpolationTicks : tickIntervalTicks;
    }
}
