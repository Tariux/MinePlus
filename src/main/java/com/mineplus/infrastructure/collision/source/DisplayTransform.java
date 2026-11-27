package com.mineplus.infrastructure.collision.source;

/**
 * The item-display transform applied to a model before voxelisation.
 *
 * <p>Model JSON coordinates are 1/16 block. Forward mapping:
 * {@code block = R_yaw( (point/16) * scale ) + translation/16}. Yaw rotates
 * around the model origin (the block corner). This mirrors what
 * {@code ItemDisplay} does with a resource-pack model, so the collision volume
 * lands where the client draws the model.</p>
 *
 * @param scaleX      scale on X
 * @param scaleY      scale on Y
 * @param scaleZ      scale on Z
 * @param translateX  translation in 1/16-block units
 * @param translateY  translation in 1/16-block units
 * @param translateZ  translation in 1/16-block units
 * @param yawDegrees  yaw in degrees, clockwise seen from above
 */
public record DisplayTransform(double scaleX, double scaleY, double scaleZ,
        double translateX, double translateY, double translateZ, double yawDegrees) {

    public static final DisplayTransform IDENTITY =
            new DisplayTransform(1, 1, 1, 0, 0, 0, 0);

    public DisplayTransform {
        if (scaleX == 0 || scaleY == 0 || scaleZ == 0) {
            throw new IllegalArgumentException("display scale must be non-zero");
        }
    }

    /** Uniformly scaled model. */
    public static DisplayTransform scaled(double scale) {
        return new DisplayTransform(scale, scale, scale, 0, 0, 0, 0);
    }

    public static DisplayTransform of(double scaleX, double scaleY, double scaleZ,
            double translateX, double translateY, double translateZ, double yawDegrees) {
        return new DisplayTransform(scaleX, scaleY, scaleZ, translateX, translateY,
                translateZ, yawDegrees);
    }

    /** Model units (1/16 block) to block coordinates. */
    public double[] forward(double x, double y, double z) {
        double px = x / 16.0 * scaleX;
        double py = y / 16.0 * scaleY;
        double pz = z / 16.0 * scaleZ;
        double[] rotated = rotate(px, pz, Math.toRadians(yawDegrees));
        return new double[] {rotated[0] + translateX / 16.0, py + translateY / 16.0,
                rotated[1] + translateZ / 16.0};
    }

    /** Block coordinates back to model units (1/16 block). */
    public double[] inverse(double x, double y, double z) {
        double px = x - translateX / 16.0;
        double py = y - translateY / 16.0;
        double pz = z - translateZ / 16.0;
        double[] rotated = rotate(px, pz, Math.toRadians(-yawDegrees));
        return new double[] {rotated[0] * 16.0 / scaleX, py * 16.0 / scaleY,
                rotated[1] * 16.0 / scaleZ};
    }

    private static double[] rotate(double x, double z, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[] {cos * x + sin * z, -sin * x + cos * z};
    }

    /** Stable key for fit caches. */
    public String cacheKey() {
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f",
                scaleX, scaleY, scaleZ, translateX, translateY, translateZ, yawDegrees);
    }
}
