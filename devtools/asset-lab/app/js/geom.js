// Pure math for the Asset Lab viewer — column-major mat4 helpers plus the rig
// pose math mirroring the server's BoneTransformMath and ModelAnimation.Curve
// exactly, so browser previews cannot drift from in-game rendering.

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

// ---------------------------------------------------------------- quaternions

const DEG = Math.PI / 180;

export function quatMultiply(a, b) {
    const [ax, ay, az, aw] = a;
    const [bx, by, bz, bw] = b;
    return [
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ];
}

/**
 * Euler degrees (x → y → z application order) as a quaternion — the matrix
 * product Rz·Ry·Rx, exactly BoneTransformMath.eulerZyxDegrees.
 */
export function quatEulerZyx(rxDeg, ryDeg, rzDeg) {
    let q = [0, 0, Math.sin(rzDeg * DEG / 2), Math.cos(rzDeg * DEG / 2)];
    q = quatMultiply(q, [0, Math.sin(ryDeg * DEG / 2), 0, Math.cos(ryDeg * DEG / 2)]);
    q = quatMultiply(q, [Math.sin(rxDeg * DEG / 2), 0, 0, Math.cos(rxDeg * DEG / 2)]);
    return q;
}

export function quatRotY(deg) {
    const half = deg * DEG / 2;
    return [0, Math.sin(half), 0, Math.cos(half)];
}

export function quatRotAxis(axis, deg) {
    const half = deg * DEG / 2;
    const s = Math.sin(half);
    if (axis === 'x') return [s, 0, 0, Math.cos(half)];
    if (axis === 'y') return [0, s, 0, Math.cos(half)];
    return [0, 0, s, Math.cos(half)];
}

// ---------------------------------------------------------------- rig pose

const PX = 1 / 16;

/**
 * One bone's local matrix: T(relPivot/16)·R·S — the exact
 * BoneTransformMath.localMatrix composition.
 *
 * @param rel   pivot delta in model px (pivot − parentPivot, plus clip offset)
 * @param euler {x,y,z} combined degrees (rest + clip + head look)
 * @param scale combined scale multiplier
 */
export function rigLocalMatrix(rel, euler, scale) {
    const m = mat4Trs([rel[0] * PX, rel[1] * PX, rel[2] * PX],
        quatEulerZyx(euler.x, euler.y, euler.z), [scale, scale, scale]);
    return m;
}

/** Model-space yaw frame for a body yaw: R_y(180 − bodyYaw). */
export function rigYawFrame(bodyYawDeg) {
    return mat4Trs([0, 0, 0], quatRotY(180 - bodyYawDeg), [1, 1, 1]);
}

export function clamp(value, min, max) {
    return value < min ? min : Math.min(value, max);
}

export function wrapDegrees(degrees) {
    let wrapped = degrees % 360;
    if (wrapped > 180) wrapped -= 360;
    else if (wrapped <= -180) wrapped += 360;
    return wrapped;
}

/** Bukkit pitch (positive down) as the head bone's model pitch. */
export function headPitchModel(pitchDeg) {
    return -clamp(wrapDegrees(pitchDeg), -75, 75);
}

/** Bukkit head yaw delta as the head bone's model yaw (±75). */
export function headYawModel(headYawDeg, bodyYawDeg) {
    return -clamp(wrapDegrees(headYawDeg - bodyYawDeg), -75, 75);
}

// ---------------------------------------------------------------- curve sampler

/**
 * Samples one serialized curve ({times[], values[][], interpolations[]}) at a
 * time — a line-by-line mirror of ModelAnimation.Curve.sample, including the
 * clamped Catmull-Rom stencil.
 */
export function sampleCurve(curve, time, out) {
    const times = curve.times;
    const values = curve.values;
    const count = times.length;
    if (count === 0) {
        out[0] = out[1] = out[2] = 0;
        return out;
    }
    if (count === 1 || time <= times[0]) {
        out[0] = values[0][0];
        out[1] = values[0][1];
        out[2] = values[0][2];
        return out;
    }
    if (time >= times[count - 1]) {
        out[0] = values[count - 1][0];
        out[1] = values[count - 1][1];
        out[2] = values[count - 1][2];
        return out;
    }
    let next = 1;
    while (next < count && times[next] < time) next++;
    const prev = next - 1;
    const span = times[next] - times[prev];
    const blend = span <= 1e-6 ? 0 : (time - times[prev]) / span;
    const interp = curve.interpolations?.[prev] ?? 'LINEAR';
    if (interp === 'STEP') {
        out[0] = values[prev][0];
        out[1] = values[prev][1];
        out[2] = values[prev][2];
        return out;
    }
    if (interp === 'CATMULLROM') {
        const p0 = Math.max(0, prev - 1);
        const p3 = Math.min(count - 1, next + 1);
        for (let c = 0; c < 3; c++) {
            const v0 = values[p0][c];
            const v1 = values[prev][c];
            const v2 = values[next][c];
            const v3 = values[p3][c];
            const b2 = blend * blend;
            const b3 = b2 * blend;
            out[c] = 0.5 * ((2 * v1)
                + (-v0 + v2) * blend
                + (2 * v0 - 5 * v1 + 4 * v2 - v3) * b2
                + (-v0 + 3 * v1 - 3 * v2 + v3) * b3);
        }
        return out;
    }
    for (let c = 0; c < 3; c++) {
        out[c] = values[prev][c] + (values[next][c] - values[prev][c]) * blend;
    }
    return out;
}

/** Wraps a playhead like ModelAnimation.wrap (LOOP wraps, ONCE/HOLD clamp). */
export function wrapClipTime(clip, seconds) {
    const length = clip.lengthSeconds ?? 0;
    if (length <= 0) return 0;
    if (clip.loopMode !== 'LOOP') return Math.min(seconds, length);
    let wrapped = seconds % length;
    return wrapped < 0 ? wrapped + length : wrapped;
}
