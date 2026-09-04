package com.mineplus.infrastructure.virtual;

/**
 * Global virtual-rendering settings (the {@code VIRTUAL_RENDERING} section of
 * {@code settings.mp.yml}); per-model {@code .meta.json} overrides take precedence.
 */
public record VirtualRenderingSettings(
        ModelMeta.CollisionMode collisionMode,
        float collisionEpsilon,
        NonAirPolicy collisionNonAirPolicy,
        boolean rotationSnap,
        float rotationSnapThresholdDegrees,
        boolean perFaceRendering,
        ModelMeta.OriginMode originMode
) {

    public enum NonAirPolicy {
        /** Silently skip non-air blocks when placing barrier cells (legacy behavior). */
        SKIP,
        /** Abort the spawn, roll back placed barriers, and fail the placement. */
        STRICT;

        public static NonAirPolicy fromKey(String key, NonAirPolicy fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return NonAirPolicy.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public static VirtualRenderingSettings defaults() {
        return new VirtualRenderingSettings(
                ModelMeta.CollisionMode.GEOMETRY,
                 GeometryOccupancyCalculator.DEFAULT_EPSILON,
                NonAirPolicy.SKIP,
                true,
                RotationSnapper.DEFAULT_SNAP_THRESHOLD_DEGREES,
                true,
                ModelMeta.OriginMode.AUTO
        );
    }
}
