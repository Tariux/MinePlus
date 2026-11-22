package com.mineplus.infrastructure.virtual;

/**
 * Tunable placement behaviour for {@link VirtualBlockPlacementHelper}.
 *
 * @param yawSnapDegrees            grid step the player's facing snaps to (90 = vanilla block grid,
 *                                  45 or 22.5 for finer rotations; must divide 360 evenly)
 * @param faceOffset                extra distance to push the placement point along the hit-face
 *                                  normal, in blocks, on top of the default adjacent-block offset
 * @param requireAirTarget          when {@code true}, placement is refused unless the target block
 *                                  is air — use for solid furniture; {@code false} allows placing
 *                                  into grass, water and other replaceable ground
 */
public record PlacementOptions(float yawSnapDegrees, double faceOffset, boolean requireAirTarget) {

    public static final PlacementOptions DEFAULT = new PlacementOptions(90.0f, 0.0, false);

    public PlacementOptions {
        if (yawSnapDegrees <= 0 || 360.0f % yawSnapDegrees != 0) {
            throw new IllegalArgumentException("yawSnapDegrees must divide 360 evenly, got " + yawSnapDegrees);
        }
        if (faceOffset < 0 || faceOffset > 4) {
            throw new IllegalArgumentException("faceOffset must be within [0, 4], got " + faceOffset);
        }
    }

    public PlacementOptions withYawSnap(float yawSnapDegrees) {
        return new PlacementOptions(yawSnapDegrees, faceOffset, requireAirTarget);
    }

    public PlacementOptions withFaceOffset(double faceOffset) {
        return new PlacementOptions(yawSnapDegrees, faceOffset, requireAirTarget);
    }

    public PlacementOptions withAirTargetCheck(boolean requireAirTarget) {
        return new PlacementOptions(yawSnapDegrees, faceOffset, requireAirTarget);
    }
}
