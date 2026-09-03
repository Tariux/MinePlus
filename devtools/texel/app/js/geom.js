// Pure column-major mat4 math (no three.js dependency) mirroring the pipeline's
// joml transforms. Cube display matrix is T·R(leftRotation)·S·R(rightRotation) —
// the exact composition DisplayEmitter uses, so browser previews cannot drift
// from server geometry.

export function mat4Identity() {
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
}

// T(t)·R(q)·S(s)
export function mat4Trs(t, q, s) {
    const [x, y, z, w] = q;
    const x2 = x + x, y2 = y + y, z2 = z + z;
    const xx = x * x2, xy = x * y2, xz = x * z2;
    const yy = y * y2, yz = y * z2, zz = z * z2;
    const wx = w * x2, wy = w * y2, wz = w * z2;
    const m = mat4Identity();
    m[0] = (1 - (yy + zz)) * s[0];
    m[1] = (xy + wz) * s[0];
    m[2] = (xz - wy) * s[0];
    m[4] = (xy - wz) * s[1];
    m[5] = (1 - (xx + zz)) * s[1];
    m[6] = (yz + wx) * s[1];
    m[8] = (xz + wy) * s[2];
    m[9] = (yz - wx) * s[2];
    m[10] = (1 - (xx + yy)) * s[2];
    m[12] = t[0];
    m[13] = t[1];
    m[14] = t[2];
    return m;
}

export function mat4Mul(a, b) {
    const out = new Array(16);
    for (let c = 0; c < 4; c++) {
        for (let r = 0; r < 4; r++) {
            out[c * 4 + r] =
                a[r] * b[c * 4] +
                a[4 + r] * b[c * 4 + 1] +
                a[8 + r] * b[c * 4 + 2] +
                a[12 + r] * b[c * 4 + 3];
        }
    }
    return out;
}

export function mat4Translate(x, y, z) {
    const m = mat4Identity();
    m[12] = x;
    m[13] = y;
    m[14] = z;
    return m;
}

export function mat4Scale(x, y, z) {
    const m = mat4Identity();
    m[0] = x;
    m[5] = y;
    m[10] = z;
    return m;
}

/** Axis-aligned unit-space box placement matrix: T(center)·S(size). */
export function mat4Box(min, max) {
    return mat4Mul(
        mat4Translate((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2),
        mat4Scale(max[0] - min[0], max[1] - min[1], max[2] - min[2]),
    );
}

/** Full cube display matrix (T·R·S·R) for a daemon cube record. */
export function cubeMatrix(cube) {
    return mat4Mul(
        mat4Trs(cube.t, cube.lr, cube.s),
        mat4Trs([0, 0, 0], cube.rr, [1, 1, 1]),
    );
}

// Face axis conventions — must match CubeFace.java exactly.
export const FACE_AXES = {
    north: { n: 2, pos: false, u: 0, v: 1 },
    south: { n: 2, pos: true, u: 0, v: 1 },
    west: { n: 0, pos: false, u: 2, v: 1 },
    east: { n: 0, pos: true, u: 2, v: 1 },
    up: { n: 1, pos: true, u: 0, v: 2 },
    down: { n: 1, pos: false, u: 0, v: 2 },
};

/** Plate surface offsets in blocks — mirrors DisplayEmitter's TEXEL_EPS values. */
const PLATE_BODY = 1 / 128;
const PLATE_OUT = 1 / 256;

/**
 * World matrix for one texel plate rect, matching DisplayEmitter's placement:
 * grid cell (x,y) spans [x/gw,(x+w)/gw] on the face's U axis and
 * [1-(y+h)/gh, 1-y/gh] on V (row 0 = top); the plate is a thin shell whose
 * outer surface sits PLATE_OUT (world blocks) beyond the face plane, body
 * PLATE_BODY inward — constant WORLD offsets, so the local-space extents are
 * divided by the cube's normal-axis scale, exactly like the baker's
 * `PLATE_SURFACE_OFFSET_BLOCKS / normalScale` probe math.
 *
 * @param M          cube display matrix (from cubeMatrix)
 * @param face       face key ('north', ...)
 * @param rect       [x, y, w, h, paletteIndex]
 * @param gw         grid width in cells
 * @param gh         grid height in cells
 * @param cubeScale  the cube's scale vector (cube.s)
 */
export function plateMatrix(M, face, rect, gw, gh, cubeScale) {
    const ax = FACE_AXES[face];
    const [x, y, w, h] = rect;
    const normalScale = Math.max(Math.abs(cubeScale[ax.n]), 1e-6);
    const outLocal = PLATE_OUT / normalScale;
    const bodyLocal = PLATE_BODY / normalScale;
    const min = [0, 0, 0];
    const max = [1, 1, 1];
    min[ax.u] = x / gw;
    max[ax.u] = (x + w) / gw;
    min[ax.v] = 1 - (y + h) / gh;
    max[ax.v] = 1 - y / gh;
    if (ax.pos) {
        min[ax.n] = 1 - bodyLocal;
        max[ax.n] = 1 + outLocal;
    } else {
        min[ax.n] = -outLocal;
        max[ax.n] = bodyLocal;
    }
    return mat4Mul(M, mat4Box(min, max));
}

/** World matrix for a voxel run [x, y, z, lengthX, widthZ, palette, emission]. */
export function voxelRunMatrix(run) {
    const [x, y, z, lx, wz] = run;
    return mat4Box([x, y, z], [x + lx, y + 1, z + wz]);
}

/** CSS #rrggbb for a packed 0xRRGGBB palette rgb. */
export function cssRgb(packed) {
    return '#' + packed.toString(16).padStart(6, '0');
}
