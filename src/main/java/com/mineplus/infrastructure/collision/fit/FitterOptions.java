package com.mineplus.infrastructure.collision.fit;

import java.util.Locale;

/** Deterministic limits and switches for one fit run. */
public record FitterOptions(
        int maxBlocks,
        int maxEntities,
        boolean allowGhostBlocks,
        boolean allowShulkerEntities,
        boolean allowVisibleBlocks,
        boolean allowInteraction,
        boolean allowPassableOutline,
        boolean allowPlates,
        boolean pruneInterior,
        RotationSnap rotationSnap) {

    public FitterOptions {
        maxBlocks = maxBlocks <= 0 ? 512 : maxBlocks;
        maxEntities = maxEntities < 0 ? 64 : maxEntities;
        rotationSnap = rotationSnap == null ? RotationSnap.QUARTER_TURNS : rotationSnap;
    }

    public static FitterOptions defaults() {
        return new FitterOptions(512, 64, true, true, false, true, true, false, true,
                RotationSnap.QUARTER_TURNS);
    }

    /** Profile permissions combined with the hard options: options can only restrict. */
    public FitProfile restrict(FitProfile profile) {
        if (profile == null) {
            return FitProfile.preset(ProfilePreset.BALANCED);
        }
        return profile.toBuilder()
                .ghost(profile.allowGhostBlocks() && allowGhostBlocks)
                .shulker(profile.allowShulkerEntities() && allowShulkerEntities)
                .visibleBlocks(profile.allowVisibleBlocks() && allowVisibleBlocks)
                .interaction(profile.allowInteraction() && allowInteraction)
                .passableOutline(profile.allowPassableOutline() && allowPassableOutline)
                .plates(profile.allowPlates() && allowPlates)
                .build();
    }

    /** How the requested yaw is snapped before fitting. */
    public enum RotationSnap {
        /** No snapping; the occupancy grid is rotated exactly as asked. */
        NONE,
        /** Snap to the nearest quarter turn (0/90/180/270). */
        QUARTER_TURNS;

        /** Snapped yaw in degrees. */
        public double snap(double yawDegrees) {
            if (this == NONE) {
                return yawDegrees;
            }
            return Math.floorMod(Math.round(yawDegrees / 90.0) * 90, 360);
        }

        public static RotationSnap fromKey(String key, RotationSnap fallback) {
            if (key == null) {
                return fallback;
            }
            for (RotationSnap value : values()) {
                if (value.name().equalsIgnoreCase(key.trim().toLowerCase(Locale.ROOT))) {
                    return value;
                }
            }
            return fallback;
        }
    }
}
