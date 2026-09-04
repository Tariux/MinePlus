package com.mineplus.infrastructure.virtual;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Geometry-aware collision: a barrier cell is occupied iff actual cube geometry
 * intersects it. Cells are computed from the exact same world transform the
 * displays use ({@code T(anchorOffset)·R·M_cube}), so the barrier lattice and
 * the rendered geometry can never drift apart.
 *
 * <p>Per cube: build its OBB, run a separating-axis test against every candidate
 * cell in the OBB's AABB range. An axis-aligned fast path (identity cube rotation
 * + signed permutation placement) reduces the SAT to three interval comparisons.
 * Negative model coordinates are fully supported. All SAT scratch vectors are
 * {@link ThreadLocal} and the hot loop is allocation-free.</p>
 */
public final class GeometryOccupancyCalculator {

    /** Cell shrink epsilon so faces exactly touching a cell boundary do not count as overlap. */
    public static final float DEFAULT_EPSILON = 1.0f / 1024.0f;
    private static final int CACHE_MAX_ENTRIES = 512;

    private static final Vector3f CELL_AXIS_X = new Vector3f(1, 0, 0);
    private static final Vector3f CELL_AXIS_Y = new Vector3f(0, 1, 0);
    private static final Vector3f CELL_AXIS_Z = new Vector3f(0, 0, 1);

    private final Map<CacheKey, int[]> cache = new HashMap<>();

    private static final ThreadLocal<Vector3f[]> SCRATCH_OBB_AXES = ThreadLocal.withInitial(() -> new Vector3f[]{
            new Vector3f(), new Vector3f(), new Vector3f()
    });
    private static final ThreadLocal<Vector3f> SCRATCH_CENTER = ThreadLocal.withInitial(Vector3f::new);
    private static final ThreadLocal<Vector3f> SCRATCH_T = ThreadLocal.withInitial(Vector3f::new);
    private static final ThreadLocal<Vector3f> SCRATCH_A = ThreadLocal.withInitial(Vector3f::new);

    public void clearCache() {
        cache.clear();
    }

    public int cacheSize() {
        return cache.size();
    }

    public synchronized int[] compute(
            VirtualModel model,
            RotationSnapper.SnappedRotation snapped,
            Quaternionf rawRotation,
            ModelMeta.CollisionMode mode,
            float epsilon,
            ModelMeta.OriginMode originMode
    ) {
        ModelMeta.CollisionMode effectiveMode = mode == null ? ModelMeta.CollisionMode.GEOMETRY : mode;
        ModelMeta.OriginMode effectiveOrigin = originMode == null || originMode == ModelMeta.OriginMode.AUTO
                ? ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes()) : originMode;
        float offsetX = effectiveOrigin == ModelMeta.OriginMode.GRID ? 0.0f : 0.5f;
        float offsetZ = offsetX;

        Quaternionf rotation = snapped != null ? snapped.quaternion() : (rawRotation == null ? new Quaternionf() : new Quaternionf(rawRotation).normalize());

        int orientationIndex = snapped != null ? snapped.orientationIndex() : -1;
        boolean cacheable = snapped != null;
        CacheKey key = new CacheKey(model.name(), orientationIndex, effectiveMode, effectiveOrigin);
        int[] cached = cacheable ? cache.get(key) : null;
        if (cached != null) return cached;

        Matrix4f placement = new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotate(rotation)
                .translate(offsetX - 0.5f, -0.5f, offsetZ - 0.5f);
        boolean permutationPlacement = isPermutation(placement);

        LongSet cells = new LongOpenHashSet();
        Matrix4f cubeMatrix = new Matrix4f();
        Matrix4f world = new Matrix4f();
        Vector3f p = new Vector3f();

        for (BakedCube cube : model.cubes()) {
            cubeMatrix.identity()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());
            placement.mul(cubeMatrix, world);

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

            for (int corner = 0; corner < 8; corner++) {
                p.set((corner & 1) == 0 ? 0.0f : 1.0f, (corner & 2) == 0 ? 0.0f : 1.0f, (corner & 4) == 0 ? 0.0f : 1.0f);
                world.transformPosition(p);
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
            }

            int cx0 = (int) Math.floor(minX), cy0 = (int) Math.floor(minY), cz0 = (int) Math.floor(minZ);
            int cx1 = (int) Math.ceil(maxX), cy1 = (int) Math.ceil(maxY), cz1 = (int) Math.ceil(maxZ);

            boolean axisAligned = cube.isAxisAligned() && permutationPlacement;
            for (int cx = cx0; cx < cx1; cx++) {
                for (int cy = cy0; cy < cy1; cy++) {
                    for (int cz = cz0; cz < cz1; cz++) {
                        long cellKey = pack(cx, cy, cz);
                        if (cells.contains(cellKey)) continue;
                        boolean occupied = axisAligned
                                ? aabbOverlapsCell(minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, epsilon)
                                : obbIntersectsCell(world, cx, cy, cz, epsilon);
                        if (occupied) cells.add(cellKey);
                    }
                }
            }
        }

        if (effectiveMode == ModelMeta.CollisionMode.SURFACE) {
            hollowInterior(cells);
        }

        int[] packed = new int[cells.size() * 3];
        int index = 0;
        for (long cell : cells) {
            packed[index++] = unpackX(cell);
            packed[index++] = unpackY(cell);
            packed[index++] = unpackZ(cell);
        }

        if (cacheable) {
            if (cache.size() >= CACHE_MAX_ENTRIES) cache.clear();
            cache.put(key, packed);
        }
        return packed;
    }

    private static boolean isPermutation(Matrix4f m) {
        for (int col = 0; col < 3; col++) {
            boolean found = false;
            for (int row = 0; row < 3; row++) {
                float abs = Math.abs(m.get(col, row));
                if (abs > 0.5f) {
                    if (found || Math.abs(abs - 1.0f) > 1.0e-4f) return false;
                    found = true;
                } else if (abs > 1.0e-4f) {
                    return false;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean aabbOverlapsCell(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int cx, int cy, int cz, float eps) {
        return maxX > cx + eps && minX < cx + 1 - eps && maxY > cy + eps && minY < cy + 1 - eps && maxZ > cz + eps && minZ < cz + 1 - eps;
    }

    /**
     * Separating-axis test between the local unit cube [0,1]^3 transformed by
     * {@code world} and the axis-aligned cell [c, c+1]^3 shrunk by {@code eps}.
     * 15 axes: 3 cell axes, 3 OBB axes, 9 cross products.
     */
    private static boolean obbIntersectsCell(Matrix4f world, int cx, int cy, int cz, float eps) {
        Vector3f[] obbAxes = SCRATCH_OBB_AXES.get();
        obbAxes[0].set(world.m00(), world.m01(), world.m02());
        obbAxes[1].set(world.m10(), world.m11(), world.m12());
        obbAxes[2].set(world.m20(), world.m21(), world.m22());

        // A fully degenerate cube (all axes zero) occupies nothing.
        if (obbAxes[0].lengthSquared() < 1.0e-12f && obbAxes[1].lengthSquared() < 1.0e-12f && obbAxes[2].lengthSquared() < 1.0e-12f) return false;

        // OBB center = image of the local cube center (0.5, 0.5, 0.5).
        Vector3f center = SCRATCH_CENTER.get().set(world.m30(), world.m31(), world.m32())
                .add(obbAxes[0]).add(obbAxes[1]).add(obbAxes[2]).mul(0.5f);

        float cellHalf = 0.5f - eps;
        Vector3f t = SCRATCH_T.get().set(center).sub(cx + 0.5f, cy + 0.5f, cz + 0.5f);
        Vector3f a = SCRATCH_A.get();

        if (separated(t, CELL_AXIS_X, obbAxes, cellHalf)) return false;
        if (separated(t, CELL_AXIS_Y, obbAxes, cellHalf)) return false;
        if (separated(t, CELL_AXIS_Z, obbAxes, cellHalf)) return false;
        for (Vector3f oa : obbAxes) if (oa.lengthSquared() >= 1.0e-12f && separated(t, oa, obbAxes, cellHalf)) return false;

        for (int i = 0; i < 3; i++) {
            Vector3f cellAxis = i == 0 ? CELL_AXIS_X : i == 1 ? CELL_AXIS_Y : CELL_AXIS_Z;
            for (int j = 0; j < 3; j++) {
                a.set(cellAxis).cross(obbAxes[j]);
                if (a.lengthSquared() >= 1.0e-12f && separated(t, a, obbAxes, cellHalf)) return false;
            }
        }
        return true;
    }

    private static boolean separated(Vector3f t, Vector3f a, Vector3f[] obbAxes, float cellHalf) {
        float obbRadius = 0.5f * (Math.abs(obbAxes[0].dot(a)) + Math.abs(obbAxes[1].dot(a)) + Math.abs(obbAxes[2].dot(a)));
        float cellRadius = cellHalf * (Math.abs(a.x) + Math.abs(a.y) + Math.abs(a.z));
        return Math.abs(t.dot(a)) > obbRadius + cellRadius;
    }

    static void hollowInterior(LongSet cells) {
        LongSet interior = new LongOpenHashSet();
        for (long cell : cells) {
            int x = unpackX(cell), y = unpackY(cell), z = unpackZ(cell);
            if (cells.contains(pack(x + 1, y, z)) && cells.contains(pack(x - 1, y, z))
                    && cells.contains(pack(x, y + 1, z)) && cells.contains(pack(x, y - 1, z))
                    && cells.contains(pack(x, y, z + 1)) && cells.contains(pack(x, y, z - 1))) {
                interior.add(cell);
            }
        }
        cells.removeAll(interior);
    }

    private record CacheKey(String modelName, int orientationIndex, ModelMeta.CollisionMode mode, ModelMeta.OriginMode originMode) {}

    /**
     * Packs a cell into a long: 26-bit X at bit 38, 26-bit Z at bit 12, 12-bit Y
     * at bit 0 — all sign-extended on unpack, so cells near world borders cannot
     * overflow into each other.
     */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0xFFF));
    }

    /** Unpacks the x field of a {@link #pack packed} cell. */
    public static int unpackX(long value) { return (int) (value >> 38); }
    /** Unpacks the z field of a {@link #pack packed} cell. */
    public static int unpackZ(long value) { return (int) ((value >> 12) & 0x3FFFFFF) << 6 >> 6; }
    /** Unpacks the y field of a {@link #pack packed} cell. */
    public static int unpackY(long value) { return (int) (value & 0xFFF) << 20 >> 20; }
}
