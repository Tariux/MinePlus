package com.mineplus.pack.item;

import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import java.util.List;

/**
 * Derives an in-hand pose from a model's own geometry — the general form of
 * "which way does this thing point, and where is the grip".
 *
 * <p><b>Why a solver rather than a table.</b> A hand pose is two questions:
 * which direction does the item point, and which point of it sits in the
 * fingers. Both answers are in the vertex data of any model, so a
 * geometry-driven pass can pose <i>any</i> model — a rifle built diagonally,
 * a sword at an angle, a wand made of one tilted plate — instead of only
 * axis-aligned ones. The pass is a principal-component analysis of the model's
 * corners: the eigenvector with the largest spread is the item's long axis
 * (barrel, blade, shaft), the one with the least spread is its thickness.</p>
 *
 * <p><b>The pose.</b> Vanilla's {@code handheld} defaults were tuned for a
 * Y-long blade with the grip at the model origin. The solver therefore builds
 * one alignment rotation that carries the model's long axis onto +Y, composes
 * it ahead of the vanilla per-context rotation, and solves the translation so
 * the derived grip point lands exactly where the vanilla pose expects the grip
 * (the image of the origin). Grip point: the end of the long axis nearest the
 * model origin (authors model handles at the origin) raised 15% of the axis
 * length along it — inside the handle for a sword, inside the stock for a gun.
 * Left-hand contexts mirror the vanilla convention (Y and Z Euler negated).</p>
 *
 * <p><b>Honesty rules.</b> The caller never lets computed data override
 * authored display data — an authored hand context wins outright. A model
 * whose shortest and longest spreads are within 20% of each other (a cube) has
 * no dominant axis and is left alone rather than posed arbitrarily.</p>
 */
public final class HeldPoseSolver {

    /** The solved pose: Euler triples and translations for each hand context. */
    public record Pose(float[] firstpersonRotationRight, float[] firstpersonTranslationRight,
                float[] firstpersonRotationLeft, float[] firstpersonTranslationLeft,
                float[] thirdpersonRotationRight, float[] thirdpersonTranslationRight,
                float[] thirdpersonRotationLeft, float[] thirdpersonTranslationLeft) {}

    /** The vanilla handheld first-person anchor (see ModelJsonWriter). */
    private static final float[] FIRSTPERSON_TRANSLATION = {1.13f, 3.2f, 1.13f};
    /** The vanilla handheld third-person anchor. */
    private static final float[] THIRDPERSON_TRANSLATION = {0.0f, 4.0f, 0.5f};

    private HeldPoseSolver() {
    }

    /**
     * Solves the held pose for the model's cubes, or returns null when the
     * geometry has no dominant axis (the caller keeps the vanilla defaults).
     */
    public static Pose solve(List<ModelBone> bones) {
        // ---- corners of every cube ----
        int count = 0;
        for (ModelBone bone : bones) {
            count += bone.cubes().size() * 8;
        }
        if (count == 0) {
            return null;
        }
        float[] corners = new float[count * 3];
        int at = 0;
        for (ModelBone bone : bones) {
            // Cubes are stored relative to their own bone's pivot (the baker's
            // model-space convention), so the pivot must be added before the
            // geometry of several bones is pooled — otherwise bones with
            // off-origin pivots collapse onto the origin and the PCA sees the
            // wrong shape.
            float px = bone.pivotX();
            float py = bone.pivotY();
            float pz = bone.pivotZ();
            for (ModelCube cube : bone.cubes()) {
                float minX = Math.min(cube.fromX(), cube.toX()) - cube.inflate() + px;
                float minY = Math.min(cube.fromY(), cube.toY()) - cube.inflate() + py;
                float minZ = Math.min(cube.fromZ(), cube.toZ()) - cube.inflate() + pz;
                float maxX = Math.max(cube.fromX(), cube.toX()) + cube.inflate() + px;
                float maxY = Math.max(cube.fromY(), cube.toY()) + cube.inflate() + py;
                float maxZ = Math.max(cube.fromZ(), cube.toZ()) + cube.inflate() + pz;
                for (float x : new float[]{minX, maxX}) {
                    for (float y : new float[]{minY, maxY}) {
                        for (float z : new float[]{minZ, maxZ}) {
                            corners[at++] = x;
                            corners[at++] = y;
                            corners[at++] = z;
                        }
                    }
                }
            }
        }

        // ---- PCA: covariance of the corners, then Jacobi eigen-decomposition ----
        float[] mean = new float[3];
        for (int i = 0; i < count; i++) {
            mean[0] += corners[i * 3];
            mean[1] += corners[i * 3 + 1];
            mean[2] += corners[i * 3 + 2];
        }
        mean[0] /= count;
        mean[1] /= count;
        mean[2] /= count;
        float[] covariance = new float[9];
        for (int i = 0; i < count; i++) {
            addOuter(covariance,
                    corners[i * 3] - mean[0],
                    corners[i * 3 + 1] - mean[1],
                    corners[i * 3 + 2] - mean[2]);
        }

        float[][] axes = eigenVectors(covariance);

        // ---- true spreads along each principal axis (projection of corners) ----
        float[] spread = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                float along = corners[i * 3] * axes[axis][0]
                        + corners[i * 3 + 1] * axes[axis][1]
                        + corners[i * 3 + 2] * axes[axis][2];
                min = Math.min(min, along);
                max = Math.max(max, along);
            }
            spread[axis] = max - min;
        }
        float longest = spread[0];
        float shortest = spread[2];
        if (longest <= 0.0f || shortest > longest * 0.8f) {
            return null;
        }

        float[] forward = normalize(axes[0]);

        // ---- grip point: long-axis end nearest the origin, raised 15% along ----
        float half = longest / 2.0f;
        float[] positive = {mean[0] + forward[0] * half,
                mean[1] + forward[1] * half,
                mean[2] + forward[2] * half};
        float[] negative = {mean[0] - forward[0] * half,
                mean[1] - forward[1] * half,
                mean[2] - forward[2] * half};
        float positiveD = positive[0] * positive[0] + positive[1] * positive[1] + positive[2] * positive[2];
        float negativeD = negative[0] * negative[0] + negative[1] * negative[1] + negative[2] * negative[2];
        float[] handleEnd = positiveD <= negativeD ? positive : negative;
        float gripDistance = longest * 0.15f;
        float[] grip = {handleEnd[0] + forward[0] * gripDistance,
                handleEnd[1] + forward[1] * gripDistance,
                handleEnd[2] + forward[2] * gripDistance};

        // ---- alignment: carry the long axis onto +Y, where handheld expects it ----
        float[] align = rotateUnitToY(forward);
        float[] alignGrip = apply(align, grip);

        // ---- compose per context: R = R_hand·R_align, t = t_hand − R_hand·(s·R_align·grip) ----
        float[] rightRotation = composeEuler(0.0f, -90.0f, 25.0f, align);
        float[] rightTranslation = anchorMinus(FIRSTPERSON_TRANSLATION,
                eulerXYZ(0.0f, -90.0f, 25.0f), alignGrip, 0.68f);
        float[] thirdRotation = composeEuler(0.0f, -90.0f, 55.0f, align);
        float[] thirdTranslation = anchorMinus(THIRDPERSON_TRANSLATION,
                eulerXYZ(0.0f, -90.0f, 55.0f), alignGrip, 0.85f);

        // Left hand mirrors the vanilla convention: Y and Z Euler negated,
        // translation unchanged.
        float[] leftRotation = {rightRotation[0], -rightRotation[1], -rightRotation[2]};
        float[] thirdLeftRotation = {thirdRotation[0], -thirdRotation[1], -thirdRotation[2]};
        return new Pose(rightRotation, rightTranslation, leftRotation, rightTranslation.clone(),
                thirdRotation, thirdTranslation, thirdLeftRotation, thirdTranslation.clone());
    }

    private static void addOuter(float[] covariance, float dx, float dy, float dz) {
        covariance[0] += dx * dx;
        covariance[1] += dx * dy;
        covariance[2] += dx * dz;
        covariance[4] += dy * dy;
        covariance[5] += dy * dz;
        covariance[8] += dz * dz;
    }

    /**
     * Eigen-decomposition of a symmetric 3×3 by cyclic Jacobi rotations.
     * Returns three orthonormal eigenvectors ordered by decreasing eigenvalue:
     * row 0 is the direction of greatest spread (the long axis), row 2 the least.
     */
    private static float[][] eigenVectors(float[] symmetric) {
        float[] a = symmetric.clone();
        // The caller fills only the upper triangle (a[1]=xy, a[2]=xz, a[5]=yz);
        // the Jacobi rotations below read the full matrix, so mirror first.
        a[3] = a[1];
        a[6] = a[2];
        a[7] = a[5];
        float[] v = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (int sweep = 0; sweep < 32; sweep++) {
            float off = Math.abs(a[1]) + Math.abs(a[2]) + Math.abs(a[5]);
            if (off < 1.0e-9f) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    float apq = a[p * 3 + q];
                    if (Math.abs(apq) < 1.0e-9f) {
                        continue;
                    }
                    float theta = (a[q * 3 + q] - a[p * 3 + p]) / (2.0f * apq);
                    float t = (float) (Math.signum(theta)
                            / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0)));
                    if (theta == 0.0f) {
                        t = 1.0f;
                    }
                    float c = 1.0f / (float) Math.sqrt(t * t + 1.0);
                    float s = t * c;
                    rotatePair(a, v, p, q, c, s);
                }
            }
        }
        // Eigenvectors are the COLUMNS of the accumulated rotation V (the
        // rotations update v's columns p and q); rows would give perpendicular
        // directions for any real rotation.
        float[][] rows = {{v[0], v[3], v[6]}, {v[1], v[4], v[7]}, {v[2], v[5], v[8]}};
        float[] eigenvalues = {a[0], a[4], a[8]};
        for (int i = 0; i < 2; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (eigenvalues[j] > eigenvalues[i]) {
                    float[] tmp = rows[i];
                    rows[i] = rows[j];
                    rows[j] = tmp;
                    float t = eigenvalues[i];
                    eigenvalues[i] = eigenvalues[j];
                    eigenvalues[j] = t;
                }
            }
        }
        return rows;
    }

    /** One Jacobi rotation on the (p,q) pair, applied to the matrix and its basis. */
    private static void rotatePair(float[] a, float[] v, int p, int q, float c, float s) {
        for (int k = 0; k < 3; k++) {
            float akp = a[k * 3 + p];
            float akq = a[k * 3 + q];
            a[k * 3 + p] = c * akp - s * akq;
            a[k * 3 + q] = s * akp + c * akq;
        }
        for (int k = 0; k < 3; k++) {
            float apk = a[p * 3 + k];
            float aqk = a[q * 3 + k];
            a[p * 3 + k] = c * apk - s * aqk;
            a[q * 3 + k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 3; k++) {
            float vkp = v[k * 3 + p];
            float vkq = v[k * 3 + q];
            v[k * 3 + p] = c * vkp - s * vkq;
            v[k * 3 + q] = s * vkp + c * vkq;
        }
    }

    /**
     * The rotation matrix carrying {@code unit} onto +Y: Rodrigues' formula
     * about the axis {@code unit × Y} by {@code acos(unit·Y)}.
     */
    private static float[] rotateUnitToY(float[] unit) {
        float[] cross = {-unit[2], 0.0f, unit[0]};
        float dot = unit[1];
        float[] axis = normalize(cross);
        float angle = (float) Math.acos(Math.max(-1.0f, Math.min(1.0f, dot)));
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float t = 1.0f - c;
        float x = axis[0];
        float y = axis[1];
        float z = axis[2];
        return new float[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    /** Vanilla handheld rotation composed with the alignment, as a display Euler triple. */
    private static float[] composeEuler(float hx, float hy, float hz, float[] align) {
        return eulerToTriple(multiply(eulerXYZ(hx, hy, hz), align));
    }

    /** t = anchor − R_hand·(s·grip): puts the grip where handheld puts the origin. */
    private static float[] anchorMinus(float[] anchor, float[] hand, float[] grip, float scale) {
        float[] rotated = apply(hand, scale(grip, scale));
        return new float[]{anchor[0] - rotated[0],
                anchor[1] - rotated[1], anchor[2] - rotated[2]};
    }

    private static float[] eulerToTriple(float[] matrix) {
        // Inverse of eulerXYZ: R = Rx·Ry·Rz has row 0 = [cos y cos z, −cos y sin z,
        // sin y], so Y reads off the top-right cell and X and Z off the atan2 pairs.
        float sine = Math.max(-1.0f, Math.min(1.0f, matrix[2]));
        float yAngle = (float) Math.toDegrees(Math.asin(sine));
        float xAngle;
        float zAngle;
        if (Math.abs(Math.abs(sine) - 1.0f) < 1.0e-6f) {
            float combined = (float) Math.toDegrees(Math.atan2(matrix[3], matrix[4]));
            xAngle = sine > 0.0f ? combined : -combined;
            zAngle = 0.0f;
        } else {
            xAngle = (float) Math.toDegrees(Math.atan2(-matrix[5], matrix[8]));
            zAngle = (float) Math.toDegrees(Math.atan2(-matrix[1], matrix[0]));
        }
        return new float[]{xAngle, yAngle, zAngle};
    }

    private static float[] eulerXYZ(float x, float y, float z) {
        return multiply(multiply(rotation(0, x), rotation(1, y)), rotation(2, z));
    }

    private static float[] rotation(int axis, float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] matrix = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        int first = (axis + 1) % 3;
        int second = (axis + 2) % 3;
        matrix[first * 3 + first] = cos;
        matrix[first * 3 + second] = -sin;
        matrix[second * 3 + first] = sin;
        matrix[second * 3 + second] = cos;
        return matrix;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] out = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[row * 3 + column] = left[row * 3] * right[column]
                        + left[row * 3 + 1] * right[3 + column]
                        + left[row * 3 + 2] * right[6 + column];
            }
        }
        return out;
    }

    private static float[] apply(float[] matrix, float[] point) {
        return new float[]{
                matrix[0] * point[0] + matrix[1] * point[1] + matrix[2] * point[2],
                matrix[3] * point[0] + matrix[4] * point[1] + matrix[5] * point[2],
                matrix[6] * point[0] + matrix[7] * point[1] + matrix[8] * point[2]};
    }

    private static float[] scale(float[] point, float factor) {
        return new float[]{point[0] * factor, point[1] * factor, point[2] * factor};
    }

    private static float[] normalize(float[] vector) {
        float length = (float) Math.sqrt(vector[0] * vector[0]
                + vector[1] * vector[1] + vector[2] * vector[2]);
        if (length < 1.0e-9f) {
            return new float[]{0.0f, 1.0f, 0.0f};
        }
        return new float[]{vector[0] / length, vector[1] / length, vector[2] / length};
    }
}
