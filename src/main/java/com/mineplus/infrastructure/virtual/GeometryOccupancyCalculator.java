package com.mineplus.infrastructure.virtual;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Geometry-aware collision: a barrier cell is occupied iff actual cube
 * geometry intersects it. Replaces the legacy whole-model-AABB fill (which made internal
 * voids and empty space between disconnected geometry solid).
 *
 * <p>Cells are computed from the exact same world transform the displays use:
 * {@code T(anchorOffset) · R · M_cube}, where {@code anchorOffset} depends on the origin
 * mode (CENTER = (0.5, 0, 0.5), GRID = zero). This guarantees the barrier lattice and the
 * rendered geometry can never drift apart.
 *
 * <p>Per cube: build its OBB, run a separating-axis test against every candidate cell in
 * the OBB's AABB range. An axis-aligned fast path (identity cube rotation + signed
 * permutation placement) reduces the SAT to three interval comparisons. Negative model
 * coordinates are fully supported.
 */
public final class GeometryOccupancyCalculator {

    /** Cell shrink epsilon so faces exactly touching a cell boundary do not count as overlap. */
    public static final float DEFAULT_EPSILON = 1.0f / 1024.0f;

    private static final int CACHE_MAX_ENTRIES = 512;

    private final Map<CacheKey, int[]> cache = new HashMap<>();

    public GeometryOccupancyCalculator() {
    }

    public void clearCache() {
        cache.clear();
    }

    public int cacheSize() {
        return cache.size();
    }

    /**
     * Computes the occupied anchor-relative cells for a model under a placement rotation.
     *
     * @param model        the parsed model
     * @param snapped      snapped placement rotation (preferred; exact signed permutation)
     * @param rawRotation  raw placement rotation, used only when {@code snapped} is null
     * @param mode         collision mode (AABB delegates to the legacy whole-box fill)
     * @param epsilon      cell shrink epsilon
     * @param originMode   anchoring convention (CENTER = block-center, GRID = block corner)
     * @return occupied cells as int triples {@code [x0,y0,z0, x1,y1,z1, ...]}, anchor-relative
     */
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
                ? ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes())
                : originMode;
        float offsetX = effectiveOrigin == ModelMeta.OriginMode.GRID ? 0.0f : 0.5f;
        float offsetZ = offsetX;

        Quaternionf rotation = snapped != null
                ? snapped.quaternion()
                : (rawRotation == null ? new Quaternionf() : new Quaternionf(rawRotation).normalize());

        if (effectiveMode == ModelMeta.CollisionMode.AABB) {
            return legacyAabbCells(model, rotation, offsetX, offsetZ);
        }

        // Only snapped (signed-permutation) rotations give a deterministic cache key;
        // raw rotations vary continuously and must never be served from cache.
        int orientationIndex = snapped != null ? snapped.orientationIndex() : -1;
        boolean cacheable = snapped != null;
        CacheKey key = new CacheKey(model.name(), orientationIndex, effectiveMode, effectiveOrigin);
        int[] cached = cacheable ? cache.get(key) : null;
        if (cached != null) {
            return cached;
        }

        // World (anchor-relative) transform, identical to the display composition:
        // world = C + R·(DO + M·q − C), where C = anchor block center (0.5, 0.5, 0.5)
        // and DO = display spawn offset. Rotations therefore pivot about the block
        // center (vanilla block behavior), keeping single-block models in-block.
        Matrix4f placement = new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotate(rotation)
                .translate(offsetX - 0.5f, -0.5f, offsetZ - 0.5f);
        boolean permutationPlacement = isPermutation(placement);

        Set<Long> cells = new HashSet<>();
        for (BakedCube cube : model.cubes()) {
            Matrix4f cubeMatrix = new Matrix4f()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());
            Matrix4f world = new Matrix4f(placement).mul(cubeMatrix);

            // Exact AABB of the transformed local unit cube [0,1]^3 (vertex transform).
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = new Vector3f(
                        (corner & 1) == 0 ? 0.0f : 1.0f,
                        (corner & 2) == 0 ? 0.0f : 1.0f,
                        (corner & 4) == 0 ? 0.0f : 1.0f);
                world.transformPosition(p);
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }

            int cx0 = (int) Math.floor(minX);
            int cy0 = (int) Math.floor(minY);
            int cz0 = (int) Math.floor(minZ);
            int cx1 = (int) Math.ceil(maxX);
            int cy1 = (int) Math.ceil(maxY);
            int cz1 = (int) Math.ceil(maxZ);

            // Fast path: axis-aligned cube under a signed-permutation (or identity) rotation
            // keeps the world box axis-aligned, so interval overlap is exact.
            boolean axisAligned = cube.isAxisAligned() && permutationPlacement;
            for (int cx = cx0; cx < cx1; cx++) {
                for (int cy = cy0; cy < cy1; cy++) {
                    for (int cz = cz0; cz < cz1; cz++) {
                        if (cells.contains(pack(cx, cy, cz))) {
                            continue;
                        }
                        boolean occupied = axisAligned
                                ? aabbOverlapsCell(minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, epsilon)
                                : obbIntersectsCell(world, cx, cy, cz, epsilon);
                        if (occupied) {
                            cells.add(pack(cx, cy, cz));
                        }
                    }
                }
            }
        }

        if (effectiveMode == ModelMeta.CollisionMode.SURFACE) {
            hollowInterior(cells);
        }

        int[] packed = new int[cells.size() * 3];
        int index = 0;
        for (Long cell : cells) {
            long value = cell;
            packed[index++] = unpackX(value);
            packed[index++] = unpackY(value);
            packed[index++] = unpackZ(value);
        }

        if (cacheable) {
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(key, packed);
        }
        return packed;
    }

    /** True when the linear part is a signed permutation matrix (entries exactly 0 / ±1). */
    private static boolean isPermutation(Matrix4f m) {
        for (int col = 0; col < 3; col++) {
            boolean found = false;
            for (int row = 0; row < 3; row++) {
                float abs = Math.abs(m.get(col, row));
                if (abs > 0.5f) {
                    if (found || Math.abs(abs - 1.0f) > 1.0e-4f) {
                        return false;
                    }
                    found = true;
                } else if (abs > 1.0e-4f) {
                    return false;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean aabbOverlapsCell(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            int cx, int cy, int cz, float eps) {
        return maxX > cx + eps && minX < cx + 1 - eps
                && maxY > cy + eps && minY < cy + 1 - eps
                && maxZ > cz + eps && minZ < cz + 1 - eps;
    }

    /**
     * Separating-axis test between the local unit cube [0,1]^3 transformed by {@code world}
     * and the axis-aligned cell [c, c+1]^3 shrunk by {@code eps}. 15 axes: 3 cell axes,
     * 3 OBB axes, 9 cross products.
     */
    private static boolean obbIntersectsCell(Matrix4f world, int cx, int cy, int cz, float eps) {
        // OBB axes = columns of the linear part (images of the local unit axes; JOML m<col><row>).
        Vector3f u0 = new Vector3f(world.m00(), world.m01(), world.m02());
        Vector3f u1 = new Vector3f(world.m10(), world.m11(), world.m12());
        Vector3f u2 = new Vector3f(world.m20(), world.m21(), world.m22());

        // A fully degenerate cube (all axes zero) occupies nothing.
        if (u0.lengthSquared() < 1.0e-12f && u1.lengthSquared() < 1.0e-12f && u2.lengthSquared() < 1.0e-12f) {
            return false;
        }

        // OBB center = image of the local cube center (0.5, 0.5, 0.5).
        Vector3f center = new Vector3f(world.m30(), world.m31(), world.m32())
                .add(u0).add(u1).add(u2).mul(0.5f);

        // Cell shrunk by eps on each side.
        float cellHalf = 0.5f - eps;
        Vector3f cellCenter = new Vector3f(cx + 0.5f, cy + 0.5f, cz + 0.5f);
        Vector3f t = new Vector3f(center).sub(cellCenter);

        Vector3f[] cellAxes = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        Vector3f[] obbAxes = {u0, u1, u2};

        for (Vector3f a : cellAxes) {
            if (separated(t, a, obbAxes, cellHalf)) {
                return false;
            }
        }
        for (Vector3f a : obbAxes) {
            if (a.lengthSquared() >= 1.0e-12f && separated(t, a, obbAxes, cellHalf)) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3f a = new Vector3f(cellAxes[i]).cross(obbAxes[j]);
                if (a.lengthSquared() < 1.0e-12f) {
                    continue;
                }
                if (separated(t, a, obbAxes, cellHalf)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * SAT separation test along axis {@code a}: the OBB (axes {@code u}, half extents 0.5)
     * versus the shrunk cell (half extents {@code cellHalf} per world axis).
     */
    private static boolean separated(Vector3f t, Vector3f a, Vector3f[] obbAxes, float cellHalf) {
        float obbRadius = 0.5f * (Math.abs(obbAxes[0].dot(a)) + Math.abs(obbAxes[1].dot(a)) + Math.abs(obbAxes[2].dot(a)));
        float cellRadius = cellHalf * (Math.abs(a.x) + Math.abs(a.y) + Math.abs(a.z));
        float centerDistance = Math.abs(t.dot(a));
        return centerDistance > obbRadius + cellRadius;
    }

    /** Removes any cell whose 6 axis-neighbors are all occupied (union-interior hollowing). */
    static void hollowInterior(Set<Long> cells) {
        List<Long> interior = new ArrayList<>();
        for (Long cell : cells) {
            long value = cell;
            int x = unpackX(value);
            int y = unpackY(value);
            int z = unpackZ(value);
            if (cells.contains(pack(x + 1, y, z)) && cells.contains(pack(x - 1, y, z))
                    && cells.contains(pack(x, y + 1, z)) && cells.contains(pack(x, y - 1, z))
                    && cells.contains(pack(x, y, z + 1)) && cells.contains(pack(x, y, z - 1))) {
                interior.add(cell);
            }
        }
        cells.removeAll(interior);
    }

    /**
     * Legacy whole-model-AABB fill (compat escape hatch). The model box's corners are
     * rotated about the anchor block center — the same pivot the displays use — and the
     * resulting world AABB is filled cell-by-cell.
     */
    private static int[] legacyAabbCells(VirtualModel model, Quaternionf rotation, float offsetX, float offsetZ) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (BakedCube cube : model.cubes()) {
            Matrix4f matrix = new Matrix4f()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = new Vector3f(
                        (corner & 1) == 0 ? 0.0f : 1.0f,
                        (corner & 2) == 0 ? 0.0f : 1.0f,
                        (corner & 4) == 0 ? 0.0f : 1.0f);
                matrix.transformPosition(p);
                any = true;
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
        }

        if (!any) {
            return new int[]{0, 0, 0};
        }

        // Rotate the 8 box corners about the anchor block center: w = C + R·(DO + p − C).
        float rMinX = Float.MAX_VALUE, rMinY = Float.MAX_VALUE, rMinZ = Float.MAX_VALUE;
        float rMaxX = -Float.MAX_VALUE, rMaxY = -Float.MAX_VALUE, rMaxZ = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f p = new Vector3f(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ);
            p.add(offsetX - 0.5f, -0.5f, offsetZ - 0.5f);
            rotation.transform(p);
            p.add(0.5f, 0.5f, 0.5f);
            rMinX = Math.min(rMinX, p.x);
            rMinY = Math.min(rMinY, p.y);
            rMinZ = Math.min(rMinZ, p.z);
            rMaxX = Math.max(rMaxX, p.x);
            rMaxY = Math.max(rMaxY, p.y);
            rMaxZ = Math.max(rMaxZ, p.z);
        }
        minX = rMinX;
        minY = rMinY;
        minZ = rMinZ;
        maxX = rMaxX;
        maxY = rMaxY;
        maxZ = rMaxZ;

        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int z0 = (int) Math.floor(minZ);
        int x1 = Math.max(x0 + 1, (int) Math.ceil(maxX));
        int y1 = Math.max(y0 + 1, (int) Math.ceil(maxY));
        int z1 = Math.max(z0 + 1, (int) Math.ceil(maxZ));

        List<int[]> triples = new ArrayList<>();
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    triples.add(new int[]{x, y, z});
                }
            }
        }
        if (triples.isEmpty()) {
            triples.add(new int[]{0, 0, 0});
        }
        int[] packed = new int[triples.size() * 3];
        for (int i = 0; i < triples.size(); i++) {
            packed[i * 3] = triples.get(i)[0];
            packed[i * 3 + 1] = triples.get(i)[1];
            packed[i * 3 + 2] = triples.get(i)[2];
        }
        return packed;
    }

    private record CacheKey(String modelName, int orientationIndex, ModelMeta.CollisionMode mode,
                            ModelMeta.OriginMode originMode) {
    }

    /**
     * Packs a cell into a long: three 21-bit signed bitfields (x at 42, y at 21, z at 0).
     * All three fields are 21 bits — a 22-bit z field would overlap y's lowest bit.
     * Shared with the texel surface baker, which works in the same cell lattice.
     */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x1F_FFFF) << 42)
                | ((long) (y & 0x1F_FFFF) << 21)
                | (long) (z & 0x1F_FFFF);
    }

    /** Unpacks the x field of a {@link #pack packed} cell. */
    public static int unpackX(long value) {
        int v = (int) (value >>> 42);
        return (v << 11) >> 11;
    }

    /** Unpacks the y field of a {@link #pack packed} cell. */
    public static int unpackY(long value) {
        int v = ((int) (value >>> 21)) & 0x1F_FFFF;
        return (v << 11) >> 11;
    }

    /** Unpacks the z field of a {@link #pack packed} cell. */
    public static int unpackZ(long value) {
        int v = ((int) value) & 0x1F_FFFF;
        return (v << 11) >> 11;
    }
}
