package com.mineplus.infrastructure.virtual.display.packet;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Per-entity record of "what the clients already have". A packet is only produced
 * when the new value differs from the last sent value by more than the protocol
 * can represent anyway.
 */
public final class DirtyState {

    private static final double POS_EPSILON = 1.0E-4;   // blocks
    private static final float ROT_EPSILON = 0.05f;     // degrees (wire resolution is 1.4 deg)
    private static final float MAT_EPSILON = 1.0E-5f;

    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;
    private final Matrix4f lastTransform = new Matrix4f();
    private boolean hasTransform;

    private boolean positionDirty, rotationDirty, transformDirty;

    /** @return true if the value changed (it is now flagged dirty). NaN initial values always count as changed. */
    public boolean updatePosition(double x, double y, double z) {
        if (near(x, lastX, POS_EPSILON) && near(y, lastY, POS_EPSILON) && near(z, lastZ, POS_EPSILON)) return false;
        lastX = x; lastY = y; lastZ = z;
        positionDirty = true;
        return true;
    }

    /** Records without flagging (client-authoritative positions, e.g. passengers). */
    public void acceptPosition(double x, double y, double z) {
        lastX = x; lastY = y; lastZ = z;
    }

    public boolean updateRotation(float yaw, float pitch) {
        if (near(yaw, lastYaw, ROT_EPSILON) && near(pitch, lastPitch, ROT_EPSILON)) return false;
        lastYaw = yaw; lastPitch = pitch;
        rotationDirty = true;
        return true;
    }

    public boolean updateTransform(Matrix4fc m) {
        if (hasTransform && lastTransform.equals(m, MAT_EPSILON)) return false;
        lastTransform.set(m);
        hasTransform = true;
        transformDirty = true;
        return true;
    }

    public boolean isPositionDirty()  { return positionDirty; }
    public boolean isRotationDirty()  { return rotationDirty; }
    public boolean isTransformDirty() { return transformDirty; }
    public boolean isAnyDirty()       { return positionDirty || rotationDirty || transformDirty; }

    /** Called after the delta was fanned out to the viewers. */
    public void clear() {
        positionDirty = rotationDirty = transformDirty = false;
    }

    /** Full reset when the entity goes back to the pool. */
    public void reset() {
        clear();
        lastX = lastY = lastZ = Double.NaN;
        lastYaw = lastPitch = Float.NaN;
        hasTransform = false;
    }

    private static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }   // NaN -> false
    private static boolean near(float a, float b, float eps)    { return Math.abs(a - b) <= eps; }
}
