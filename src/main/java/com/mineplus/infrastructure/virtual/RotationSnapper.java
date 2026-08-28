package com.mineplus.infrastructure.virtual;

import com.mineplus.util.DebugLogger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Snaps an arbitrary placement rotation to the nearest of the 24 orientation-preserving
 * signed axis permutations (the rotational symmetry group of the cube). Only these map
 * the integer voxel lattice onto itself exactly, so the same snapped rotation transforms
 * both barrier-cell offsets and display translations with pure integer arithmetic —
 * zero rounding error, collision and visuals can never drift apart.
 */
public final class RotationSnapper {

    /** Default max deviation between the input rotation and its snapped form before a warning is logged. */
    public static final float DEFAULT_SNAP_THRESHOLD_DEGREES = 5.0f;

    private RotationSnapper() {
    }

    /** A snapped rotation: signed axis permutation plus the exact quaternion. */
    public static final class SnappedRotation {
        /** outputAxis[i] = input axis that maps onto output axis i. */
        final int[] axisMap = new int[3];
        /** outputSign[i] = sign of the mapped axis. */
        final int[] signs = new int[3];
        final Quaternionf quaternion = new Quaternionf();
        final float deviationDegrees;

        SnappedRotation(int[] axisMap, int[] signs, Quaternionf quaternion, float deviationDegrees) {
            System.arraycopy(axisMap, 0, this.axisMap, 0, 3);
            System.arraycopy(signs, 0, this.signs, 0, 3);
            this.quaternion.set(quaternion);
            this.deviationDegrees = deviationDegrees;
        }

        public Quaternionf quaternion() {
            return new Quaternionf(quaternion);
        }

        public float deviationDegrees() {
            return deviationDegrees;
        }

        public int orientationIndex() {
            int index = 0;
            for (int i = 0; i < 3; i++) {
                index = index * 6 + (axisMap[i] * 2 + (signs[i] < 0 ? 1 : 0));
            }
            return index;
        }

        /** Exact integer transform of a voxel offset: out[i] = signs[i] * v[axisMap[i]]. */
        public Vector3i transform(int x, int y, int z) {
            int[] v = {x, y, z};
            return new Vector3i(
                    signs[0] * v[axisMap[0]],
                    signs[1] * v[axisMap[1]],
                    signs[2] * v[axisMap[2]]
            );
        }

        /** Vector transform (display translations): rotates by the exact snapped quaternion. */
        public Vector3f transform(Vector3f v) {
            return quaternion.transform(new Vector3f(v));
        }
    }

    /** Immutable integer 3-tuple used for exact voxel-offset math. */
    public record Vector3i(int x, int y, int z) {
    }

    public static SnappedRotation snap(Quaternionf q) {
        return snap(q, DEFAULT_SNAP_THRESHOLD_DEGREES);
    }

    public static SnappedRotation snap(Quaternionf q, float thresholdDegrees) {
        Quaternionf normalized = new Quaternionf(q).normalize();
        Matrix3f r = new Matrix3f().rotation(normalized);

        // JOML element access: get(col, row) — column c of the rotation matrix is the
        // image of input axis e_c. For each input axis, find the dominant output axis.
        int[] axisMap = new int[3];   // axisMap[output] = input
        int[] signs = new int[3];     // signs[output] = sign of the mapped axis
        boolean[] filled = new boolean[3];
        for (int inputAxis = 0; inputAxis < 3; inputAxis++) {
            int dominantRow = 0;
            float best = -1.0f;
            for (int row = 0; row < 3; row++) {
                float abs = Math.abs(r.get(inputAxis, row));
                if (abs > best) {
                    best = abs;
                    dominantRow = row;
                }
            }
            axisMap[dominantRow] = inputAxis;
            signs[dominantRow] = r.get(inputAxis, dominantRow) >= 0.0f ? 1 : -1;
            filled[dominantRow] = true;
        }

        // Repair mirrored / degenerate inputs: enforce a proper permutation with det = +1.
        boolean properPermutation = filled[0] && filled[1] && filled[2];
        if (!properPermutation) {
            axisMap = new int[]{0, 1, 2};
            signs = new int[]{1, 1, 1};
        } else if (determinant(axisMap, signs) < 0) {
            signs[2] = -signs[2];
        }

        // Deviation: max angle between each input axis's actual image and its snapped target.
        float maxDeviation = 0.0f;
        for (int inputAxis = 0; inputAxis < 3; inputAxis++) {
            Vector3f actual = new Vector3f(
                    r.get(inputAxis, 0),
                    r.get(inputAxis, 1),
                    r.get(inputAxis, 2));
            Vector3f snapped = new Vector3f();
            for (int outputAxis = 0; outputAxis < 3; outputAxis++) {
                if (axisMap[outputAxis] == inputAxis) {
                    snapped.setComponent(outputAxis, signs[outputAxis]);
                }
            }
            float dot = actual.dot(snapped);
            dot = Math.max(-1.0f, Math.min(1.0f, dot));
            float angle = (float) Math.toDegrees(Math.acos(dot));
            maxDeviation = Math.max(maxDeviation, angle);
        }

        // Rebuild the exact snapped matrix: column = input axis, row = output axis.
        // Must start from zero — new Matrix4f() is identity and would contaminate the
        // permutation with stale diagonal entries.
        Matrix4f snappedMatrix = new Matrix4f().zero();
        snappedMatrix.m33(1.0f);
        for (int outputAxis = 0; outputAxis < 3; outputAxis++) {
            snappedMatrix.set(axisMap[outputAxis], outputAxis, (float) signs[outputAxis]);
        }
        Quaternionf snappedQuaternion = snappedMatrix.getUnnormalizedRotation(new Quaternionf()).normalize();

        if (maxDeviation > thresholdDegrees) {
            DebugLogger.warning("RotationSnapper: placement rotation deviates " + String.format("%.1f", maxDeviation)
                    + "deg from the nearest grid orientation (threshold " + thresholdDegrees
                    + "deg); snapping is enforced anyway.");
        }

        return new SnappedRotation(axisMap, signs, snappedQuaternion, maxDeviation);
    }

    /** Determinant of the signed permutation matrix M[row=output][col=input]. */
    private static int determinant(int[] axisMap, int[] signs) {
        int[][] m = new int[3][3];
        for (int output = 0; output < 3; output++) {
            m[output][axisMap[output]] = signs[output];
        }
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
}
