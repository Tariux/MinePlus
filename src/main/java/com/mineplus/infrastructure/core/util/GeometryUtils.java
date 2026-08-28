package com.mineplus.infrastructure.core.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Vector-hook geometry primitives. The swept-segment test is the JOML-native port of
 * FMM's {@code OBBHitDetection.projectileIntersectionDistance}: rather than testing only
 * an entity's instantaneous (and possibly sparse) position each tick, it ray-casts the
 * whole moved segment so a fast mover cannot tunnel through a thin anchor/box between
 * samples.
 */
public final class GeometryUtils {

    private GeometryUtils() {
    }

    /**
     * @return the distance along {@code [start, end]} at which the segment first
     *         intersects the oriented box, or {@code -1} if it misses.
     */
    public static double segmentHit(Vector3f start, Vector3f end, Matrix4f boxTransform, Vector3f halfExtents) {
        float len = start.distance(end);
        if (len < 1e-6f) {
            return -1f;
        }
        Matrix4f inv = boxTransform.invert(new Matrix4f());
        Vector3f localStart = inv.transformPosition(start, new Vector3f());
        Vector3f localEnd = inv.transformPosition(end, new Vector3f());
        double t = slabClip(localStart, localEnd, halfExtents);
        return (t >= 0.0 && t <= len) ? t : -1.0;
    }

    /** Standard AABB slab test in box-local space; returns entry t in [0,1] or -1. */
    private static double slabClip(Vector3f a, Vector3f b, Vector3f half) {
        double tMin = 0.0;
        double tMax = 1.0;
        double[] origin = {a.x, a.y, a.z};
        double[] dir = {b.x - a.x, b.y - a.y, b.z - a.z};
        double[] h = {half.x, half.y, half.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-9) {
                if (origin[i] < -h[i] || origin[i] > h[i]) {
                    return -1.0;
                }
                continue;
            }
            double t1 = (-h[i] - origin[i]) / dir[i];
            double t2 = (h[i] - origin[i]) / dir[i];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1.0;
            }
        }
        return tMin;
    }
}
