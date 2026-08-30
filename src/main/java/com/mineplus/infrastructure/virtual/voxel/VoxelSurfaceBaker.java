package com.mineplus.infrastructure.virtual.voxel;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.VoxelOccupancyCalculator;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import com.mineplus.infrastructure.virtual.texel.TexelPalette;
import com.mineplus.infrastructure.virtual.texel.TexelSampler;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Load-time voxel reconstruction: rasterizes the model's real geometry onto the
 * 1x1x1 voxel lattice of its origin mode, samples — for every occupied voxel —
 * the texture texels the geometry actually maps onto it, quantizes each voxel's
 * area-weighted color to the flat vanilla palette, and merges same-color voxels
 * into single-display XZ rectangles (greedy meshing per Y level).
 *
 * <p>The pipeline answers, per voxel: <i>what should occupy this exact
 * 1x1x1 model-space voxel after considering the model geometry and the texture
 * mapped onto it?</i> The answer is derived from the geometry-UV relationship,
 * never from treating texture pixels as independent blocks:
 * <ol>
 *   <li><b>Occupancy.</b> Each cube is transformed by its display matrix
 *       ({@code T·R·S·R}, the exact transform the classic renderer uses) and
 *       every candidate lattice cell is intersected with it in the cube's local
 *       unit space (exact for axis-aligned cubes, conservative for rotated
 *       ones). The lattice is shifted by the origin mode
 *       ({@link RenderStrategySelector#latticeShift}) so voxels coincide with
 *       actual world blocks.</li>
 *   <li><b>Surfaces.</b> A voxel accumulates one sample per cube face whose
 *       plane passes through it — the min/max face planes of every cube
 *       overlapping the voxel — weighted by the face's cut area inside the
 *       voxel. For exact geometry (all cubes axis-aligned with lattice-snapped
 *       bounds) every face plane lies on a voxel boundary, so each sample is
 *       additionally gated on the neighboring voxel across that boundary being
 *       empty: buried seam faces between abutting cubes never bleed their
 *       texels into the voxel color, exactly like the texel baker's occlusion
 *       culling. Approximated geometry (voxelMode ON with off-lattice cubes)
 *       samples every triggering face.</li>
 *   <li><b>Texels.</b> Each face sample resolves the real UV window through the
 *       shared {@link TexelSampler} (window mapping, in-plane rotation,
 *       cutout alpha, PNG resolution scaling — identical to texel surface
 *       baking), so voxel color and texel plates can never disagree.</li>
 *   <li><b>Materials.</b> The weighted average quantizes to the nearest
 *       visually-flat vanilla block via {@link TexelPalette} (with the same
 *       bounded run-continuity hysteresis the texel baker uses); untextured
 *       faces contribute the pipeline's neutral surface tone.</li>
 *   <li><b>Emission.</b> A voxel's light emission is the maximum emission of
 *       the cubes overlapping it.</li>
 * </ol>
 *
 * <p><b>Interior culling.</b> When the geometry is exact, any voxel whose six
 * lattice neighbors are all occupied is fully enclosed by rendered voxels and
 * is dropped — the same union-interior rule as SURFACE collision hollowing.
 * For approximated geometry no voxel is culled, because "occupied" there only
 * means "partially covered".
 *
 * <p><b>Run merging.</b> Surviving voxels are greedily merged into XZ-plane
 * rectangles per Y level (scan order deterministic: y, then z, then x): a
 * uniform 16x16 floor collapses from 16 X-runs to a single display. Models
 * with extreme XZ spread fall back to X-only runs rather than allocating huge
 * merge grids.
 *
 * <p><b>Performance.</b> Per-cube geometry (inverse matrix, model-space AABB,
 * candidate cell ranges) is precomputed once and shared by both passes; span
 * computation is allocation-free — axis-aligned cubes (the AUTO-eligible
 * majority) resolve spans analytically from translation/scale with no matrix
 * transforms at all, rotated cubes transform cell corners through a reused
 * scratch vector. Baking a 64^3-class model therefore produces zero garbage
 * per probed cell.
 *
 * <p><b>Guards.</b> A probed-cell ceiling aborts the bake for pathological
 * models and a display budget falls back to the legacy pipeline; bake failures
 * never break model load.
 */
public final class VoxelSurfaceBaker {

    private VoxelSurfaceBaker() {
    }

    /** Strict-overlap epsilon per axis (mirrors the collision calculator's cell shrink). */
    private static final float OVERLAP_EPS = 1.0f / 1024.0f;

    /**
     * Face-plane proximity: a voxel whose local intersection touches a cube face
     * plane within this distance samples that face. Grid-snapped geometry lands
     * exactly on planes; the epsilon only absorbs float rounding.
     */
    private static final float FACE_EPS = 1.0f / 256.0f;

    /** Hard rasterization guard: total candidate cells probed across all cubes. */
    private static final int MAX_PROBED_CELLS = 262_144;

    /** Merge-grid guard: XZ cell budget for rectangle merging per model. */
    private static final long MAX_MERGE_GRID_CELLS = 1L << 20;

    /** Run-continuity hysteresis for palette matching (same value as texel baking). */
    private static final float MATCH_TIE_TOLERANCE = 1.20f;

    /** Deterministic voxel iteration order: y, then z, then x ascending. */
    private static final Comparator<Long> VOXEL_ORDER = (a, b) -> {
        int ya = VoxelOccupancyCalculator.unpackY(a);
        int yb = VoxelOccupancyCalculator.unpackY(b);
        if (ya != yb) {
            return Integer.compare(ya, yb);
        }
        int za = VoxelOccupancyCalculator.unpackZ(a);
        int zb = VoxelOccupancyCalculator.unpackZ(b);
        if (za != zb) {
            return Integer.compare(za, zb);
        }
        return Integer.compare(VoxelOccupancyCalculator.unpackX(a), VoxelOccupancyCalculator.unpackX(b));
    };

    /**
     * Bakes the voxel reconstruction plan (or the strategy decision to keep the
     * legacy pipeline) for a registered model.
     *
     * @param model             the imported model
     * @param meta              per-model overrides ({@code voxelMode}/{@code maxVoxelDisplays})
     * @param modelFile         the source {@code .bbmodel} file (next-to-model texture lookup)
     * @param imageStore        decoded-PNG cache shared across models
     * @param voxelSettings     global voxel rendering settings
     * @param renderingSettings global virtual-rendering settings
     * @param texelBake         the model's texel bake result, for legacy sub-strategy reporting
     * @param originMode        the model's resolved origin mode (voxel lattice)
     */
    public static VoxelModelBake bakeModel(
            VirtualModel model,
            ModelMeta meta,
            File modelFile,
            TextureImageStore imageStore,
            VoxelRenderingSettings voxelSettings,
            VirtualRenderingSettings renderingSettings,
            TexelBakeResult texelBake,
            ModelMeta.OriginMode originMode
    ) {
        long startNanos = System.nanoTime();
        VoxelRenderingSettings settings =
                voxelSettings == null ? VoxelRenderingSettings.defaults() : voxelSettings;

        if (model == null || imageStore == null) {
            return emptyBake(RenderStrategySelector.select(
                    model, meta, renderingSettings, settings, texelBake, originMode, false), startNanos);
        }

        // Texture resolvability scan (also warms the shared decoded-PNG cache).
        boolean anyTextureImage = false;
        for (String textureName : model.textureNames()) {
            if (imageStore.raster(textureName, modelFile) != null) {
                anyTextureImage = true;
            }
        }

        RenderStrategySelector.Selection selection = RenderStrategySelector.select(
                model, meta, renderingSettings, settings, texelBake, originMode, anyTextureImage);
        if (selection.strategy() != RenderStrategy.VOXEL) {
            return emptyBake(selection, startNanos);
        }

        Vector3f shift = RenderStrategySelector.latticeShift(originMode);
        Map<Long, CellAccum> cells = new HashMap<>();
        Map<BakedFace, TexelSampler> samplers = new HashMap<>();
        boolean exact = RenderStrategySelector.exactGeometry(model, originMode);

        // Per-cube geometry, precomputed once and shared by both passes.
        List<BakedCube> cubes = model.cubes();
        CubeGeom[] geoms = new CubeGeom[cubes.size()];
        for (int i = 0; i < cubes.size(); i++) {
            geoms[i] = CubeGeom.build(cubes.get(i), shift);
        }
        Vector3f scratch = new Vector3f();
        float[] spans = new float[6];

        // Pass 1 — occupancy and emission: mark every cell a cube's local unit
        // box strictly overlaps, tracking the max light emission per cell.
        long probed = 0;
        boolean aborted = false;
        rasterize:
        for (CubeGeom geom : geoms) {
            if (geom == null) {
                continue;
            }
            for (int cy = geom.y0; cy < geom.y1; cy++) {
                for (int cz = geom.z0; cz < geom.z1; cz++) {
                    for (int cx = geom.x0; cx < geom.x1; cx++) {
                        if (++probed > MAX_PROBED_CELLS) {
                            aborted = true;
                            break rasterize;
                        }
                        if (!cellSpans(geom, cx, cy, cz, shift, scratch, spans)) {
                            continue;
                        }
                        if (spans[1] - spans[0] <= OVERLAP_EPS
                                || spans[3] - spans[2] <= OVERLAP_EPS
                                || spans[5] - spans[4] <= OVERLAP_EPS) {
                            continue;
                        }
                        CellAccum cell = cells.computeIfAbsent(
                                VoxelOccupancyCalculator.pack(cx, cy, cz), key -> new CellAccum());
                        cell.lightEmission = Math.max(cell.lightEmission, geom.cube.lightEmission());
                    }
                }
            }
        }

        if (aborted) {
            warn("Model '" + model.name() + "': voxel bake aborted after probing "
                    + MAX_PROBED_CELLS + " cells; keeping the legacy pipeline.");
            return emptyBake(RenderStrategySelector.legacyStrategy(renderingSettings, texelBake,
                    "voxel bake aborted: model exceeds the probed-cell guard"), startNanos);
        }

        int occupiedVoxels = cells.size();

        // Pass 2 — surface sampling: every cube face plane passing through an
        // occupied voxel contributes its texel, weighted by cut area. For exact
        // geometry, samples are gated on the neighboring voxel across the face
        // plane being empty (buried seams stay invisible).
        for (CubeGeom geom : geoms) {
            if (geom == null) {
                continue;
            }
            for (int cy = geom.y0; cy < geom.y1; cy++) {
                for (int cz = geom.z0; cz < geom.z1; cz++) {
                    for (int cx = geom.x0; cx < geom.x1; cx++) {
                        CellAccum cell = cells.get(VoxelOccupancyCalculator.pack(cx, cy, cz));
                        if (cell == null) {
                            continue;
                        }
                        if (!cellSpans(geom, cx, cy, cz, shift, scratch, spans)) {
                            continue;
                        }
                        sampleCellFaces(cells, samplers, geom, cell, cx, cy, cz, spans,
                                exact, model, modelFile, imageStore);
                    }
                }
            }
        }

        // Interior culling — exact geometry only (see class javadoc). Two passes:
        // interiorhood is decided against the FULL occupancy first, then removed,
        // because removing one interior voxel during iteration would un-occupy a
        // neighbor face and let adjacent interior voxels escape culling.
        int culledInteriorVoxels = 0;
        if (exact && cells.size() > 1) {
            long[] interior = new long[cells.size()];
            int interiorCount = 0;
            for (long key : cells.keySet()) {
                int x = VoxelOccupancyCalculator.unpackX(key);
                int y = VoxelOccupancyCalculator.unpackY(key);
                int z = VoxelOccupancyCalculator.unpackZ(key);
                if (cells.containsKey(VoxelOccupancyCalculator.pack(x + 1, y, z))
                        && cells.containsKey(VoxelOccupancyCalculator.pack(x - 1, y, z))
                        && cells.containsKey(VoxelOccupancyCalculator.pack(x, y + 1, z))
                        && cells.containsKey(VoxelOccupancyCalculator.pack(x, y - 1, z))
                        && cells.containsKey(VoxelOccupancyCalculator.pack(x, y, z + 1))
                        && cells.containsKey(VoxelOccupancyCalculator.pack(x, y, z - 1))) {
                    interior[interiorCount++] = key;
                }
            }
            for (int i = 0; i < interiorCount; i++) {
                cells.remove(interior[i]);
            }
            culledInteriorVoxels = interiorCount;
        }
        int surfaceVoxels = cells.size();

        // Palette assignment with row hysteresis, then run merging.
        List<Long> orderedKeys = new ArrayList<>(cells.keySet());
        orderedKeys.sort(VOXEL_ORDER);
        long[] keys = new long[orderedKeys.size()];
        int[] indices = new int[orderedKeys.size()];
        int[] emissions = new int[orderedKeys.size()];
        Map<Integer, Integer> paletteUsage = new LinkedHashMap<>();
        int previousInRow = -1;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 0; i < keys.length; i++) {
            long key = orderedKeys.get(i);
            keys[i] = key;
            int y = VoxelOccupancyCalculator.unpackY(key);
            int z = VoxelOccupancyCalculator.unpackZ(key);
            if (y != lastY || z != lastZ) {
                previousInRow = -1;
                lastY = y;
                lastZ = z;
            }
            CellAccum cell = cells.get(key);
            int index = cell.weight > 0.0
                    ? TexelPalette.match(
                            (int) Math.round(cell.red / cell.weight),
                            (int) Math.round(cell.green / cell.weight),
                            (int) Math.round(cell.blue / cell.weight),
                            previousInRow,
                            MATCH_TIE_TOLERANCE)
                    : TexelPalette.NEUTRAL_INDEX;
            indices[i] = index;
            emissions[i] = cell.lightEmission;
            previousInRow = index;
            paletteUsage.merge(index, 1, Integer::sum);
        }

        List<VoxelModelBake.VoxelRun> runs = mergeRuns(keys, indices, emissions, shift);

        int maxDisplays = settings.effectiveMaxDisplays(meta);
        if (runs.size() > maxDisplays) {
            warn("Model '" + model.name() + "': voxel reconstruction needs " + runs.size()
                    + " display runs (budget " + maxDisplays + "); keeping the legacy pipeline. "
                    + "Raise VOXEL_RENDERING.MAX_DISPLAYS or set maxVoxelDisplays in the model's .meta.json.");
            return emptyBake(RenderStrategySelector.legacyStrategy(renderingSettings, texelBake,
                    "voxel reconstruction exceeded the display budget ("
                            + runs.size() + " > " + maxDisplays + ")"), startNanos);
        }

        DebugLogger.info("[VoxelBaking] Model '" + model.name() + "': reconstructed "
                + surfaceVoxels + " voxel(s)" + (culledInteriorVoxels > 0
                        ? " (" + culledInteriorVoxels + " interior culled)" : "")
                + " into " + runs.size() + " display run(s) in "
                + ((System.nanoTime() - startNanos) / 1_000_000.0) + " ms.");

        return new VoxelModelBake(
                RenderStrategy.VOXEL,
                selection.rationale(),
                runs,
                occupiedVoxels,
                surfaceVoxels,
                culledInteriorVoxels,
                System.nanoTime() - startNanos,
                paletteUsage
        );
    }

    /**
     * Merges palette-assigned voxels into display runs. The default path greedy-
     * merges XZ rectangles per Y level (same palette entry and light emission);
     * models whose XZ span exceeds {@link #MAX_MERGE_GRID_CELLS} fall back to
     * X-axis-only runs, which need no grid allocation.
     */
    private static List<VoxelModelBake.VoxelRun> mergeRuns(
            long[] keys, int[] indices, int[] emissions, Vector3f shift) {
        if (keys.length == 0) {
            return List.of();
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : keys) {
            minX = Math.min(minX, VoxelOccupancyCalculator.unpackX(key));
            maxX = Math.max(maxX, VoxelOccupancyCalculator.unpackX(key));
            minZ = Math.min(minZ, VoxelOccupancyCalculator.unpackZ(key));
            maxZ = Math.max(maxZ, VoxelOccupancyCalculator.unpackZ(key));
        }
        long spanX = (long) maxX - minX + 1;
        long spanZ = (long) maxZ - minZ + 1;

        List<VoxelModelBake.VoxelRun> runs = new ArrayList<>();
        if (spanX * spanZ <= MAX_MERGE_GRID_CELLS) {
            mergeRunsGrid(keys, indices, emissions, shift, minX, minZ,
                    (int) spanX, (int) spanZ, runs);
        } else {
            mergeRunsAxis(keys, indices, emissions, shift, runs);
        }
        return runs;
    }

    /**
     * Greedy XZ-rectangle merging per Y level. Keys arrive in (y, z, x) order, so
     * each Y level is a contiguous slice; its cells fill a composite-key grid
     * ({@code paletteIndex << 4 | emission}, -1 empty) that is greedy-meshed with
     * the same maximal-rectangle algorithm as {@code TexelMerge}.
     */
    private static void mergeRunsGrid(
            long[] keys, int[] indices, int[] emissions, Vector3f shift,
            int minX, int minZ, int spanX, int spanZ, List<VoxelModelBake.VoxelRun> runs) {
        int[] grid = new int[spanX * spanZ];
        boolean[] visited = new boolean[spanX * spanZ];
        int i = 0;
        while (i < keys.length) {
            int y = VoxelOccupancyCalculator.unpackY(keys[i]);
            int levelEnd = i;
            while (levelEnd < keys.length
                    && VoxelOccupancyCalculator.unpackY(keys[levelEnd]) == y) {
                levelEnd++;
            }

            java.util.Arrays.fill(grid, -1);
            java.util.Arrays.fill(visited, false);
            for (int c = i; c < levelEnd; c++) {
                int x = VoxelOccupancyCalculator.unpackX(keys[c]) - minX;
                int z = VoxelOccupancyCalculator.unpackZ(keys[c]) - minZ;
                grid[z * spanX + x] = (indices[c] << 4) | emissions[c];
            }

            for (int z = 0; z < spanZ; z++) {
                for (int x = 0; x < spanX; x++) {
                    int origin = z * spanX + x;
                    int key = grid[origin];
                    if (key < 0 || visited[origin]) {
                        continue;
                    }
                    int runWidth = 1;
                    while (x + runWidth < spanX
                            && !visited[origin + runWidth]
                            && grid[origin + runWidth] == key) {
                        runWidth++;
                    }
                    int runDepth = 1;
                    extendDepth:
                    while (z + runDepth < spanZ) {
                        int rowStart = (z + runDepth) * spanX + x;
                        for (int cx = 0; cx < runWidth; cx++) {
                            if (visited[rowStart + cx] || grid[rowStart + cx] != key) {
                                break extendDepth;
                            }
                        }
                        runDepth++;
                    }
                    for (int rz = 0; rz < runDepth; rz++) {
                        int rowStart = (z + rz) * spanX + x;
                        for (int rx = 0; rx < runWidth; rx++) {
                            visited[rowStart + rx] = true;
                        }
                    }
                    runs.add(new VoxelModelBake.VoxelRun(
                            minX + x + shift.x, y + shift.y, minZ + z + shift.z,
                            runWidth, runDepth, key >> 4, key & 0xF));
                }
            }
            i = levelEnd;
        }
    }

    /** Fallback for extreme XZ spread: maximal runs along +X within (y, z) rows. */
    private static void mergeRunsAxis(
            long[] keys, int[] indices, int[] emissions, Vector3f shift,
            List<VoxelModelBake.VoxelRun> runs) {
        int runX = 0;
        int runY = 0;
        int runZ = 0;
        int runLength = 0;
        int runKey = 0;
        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            int x = VoxelOccupancyCalculator.unpackX(key);
            int y = VoxelOccupancyCalculator.unpackY(key);
            int z = VoxelOccupancyCalculator.unpackZ(key);
            int composite = (indices[i] << 4) | emissions[i];
            if (runLength > 0 && y == runY && z == runZ && x == runX + runLength
                    && composite == runKey) {
                runLength++;
            } else {
                if (runLength > 0) {
                    runs.add(new VoxelModelBake.VoxelRun(
                            runX + shift.x, runY + shift.y, runZ + shift.z,
                            runLength, 1, runKey >> 4, runKey & 0xF));
                }
                runX = x;
                runY = y;
                runZ = z;
                runLength = 1;
                runKey = composite;
            }
        }
        if (runLength > 0) {
            runs.add(new VoxelModelBake.VoxelRun(
                    runX + shift.x, runY + shift.y, runZ + shift.z,
                    runLength, 1, runKey >> 4, runKey & 0xF));
        }
    }

    /**
     * Writes the cell's clamped [0,1] intersection intervals with the cube's
     * local unit box into {@code out} as
     * {@code {minX, maxX, minY, maxY, minZ, maxZ}}. Returns false when the
     * transform degenerates (treated as no overlap). Axis-aligned cubes resolve
     * analytically from translation/scale; rotated cubes transform the cell's
     * corners through the inverse matrix into a reused scratch vector.
     */
    private static boolean cellSpans(
            CubeGeom geom, int cx, int cy, int cz, Vector3f shift,
            Vector3f scratch, float[] out) {
        if (geom.analytic) {
            out[0] = clamp01((cx + shift.x - geom.tx) / geom.sx);
            out[1] = clamp01((cx + 1 + shift.x - geom.tx) / geom.sx);
            out[2] = clamp01((cy + shift.y - geom.ty) / geom.sy);
            out[3] = clamp01((cy + 1 + shift.y - geom.ty) / geom.sy);
            out[4] = clamp01((cz + shift.z - geom.tz) / geom.sz);
            out[5] = clamp01((cz + 1 + shift.z - geom.tz) / geom.sz);
            if (out[0] > out[1]) {
                float t = out[0];
                out[0] = out[1];
                out[1] = t;
            }
            if (out[2] > out[3]) {
                float t = out[2];
                out[2] = out[3];
                out[3] = t;
            }
            if (out[4] > out[5]) {
                float t = out[4];
                out[4] = out[5];
                out[5] = t;
            }
            return true;
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            scratch.set(
                    cx + shift.x + ((corner & 1) == 0 ? 0.0f : 1.0f),
                    cy + shift.y + ((corner & 2) == 0 ? 0.0f : 1.0f),
                    cz + shift.z + ((corner & 4) == 0 ? 0.0f : 1.0f));
            geom.inverse.transformPosition(scratch);
            if (!Float.isFinite(scratch.x) || !Float.isFinite(scratch.y)
                    || !Float.isFinite(scratch.z)) {
                return false;
            }
            minX = Math.min(minX, scratch.x);
            minY = Math.min(minY, scratch.y);
            minZ = Math.min(minZ, scratch.z);
            maxX = Math.max(maxX, scratch.x);
            maxY = Math.max(maxY, scratch.y);
            maxZ = Math.max(maxZ, scratch.z);
        }
        out[0] = Math.max(minX, 0.0f);
        out[1] = Math.min(maxX, 1.0f);
        out[2] = Math.max(minY, 0.0f);
        out[3] = Math.min(maxY, 1.0f);
        out[4] = Math.max(minZ, 0.0f);
        out[5] = Math.min(maxZ, 1.0f);
        return true;
    }

    /**
     * Samples every cube face plane passing through one occupied voxel into the
     * cell's accumulator. For exact geometry, faces whose neighboring voxel
     * (across the face plane) is occupied are buried seams and contribute
     * nothing. Spans arrive precomputed in {@code spans}
     * ({@code {minX, maxX, minY, maxY, minZ, maxZ}}, already clamped).
     */
    private static void sampleCellFaces(
            Map<Long, CellAccum> cells,
            Map<BakedFace, TexelSampler> samplers,
            CubeGeom geom,
            CellAccum cell,
            int cx,
            int cy,
            int cz,
            float[] spans,
            boolean exact,
            VirtualModel model,
            File modelFile,
            TextureImageStore imageStore
    ) {
        float xMin = spans[0];
        float xMax = spans[1];
        float yMin = spans[2];
        float yMax = spans[3];
        float zMin = spans[4];
        float zMax = spans[5];
        float xLength = xMax - xMin;
        float yLength = yMax - yMin;
        float zLength = zMax - zMin;
        if (xLength <= OVERLAP_EPS || yLength <= OVERLAP_EPS || zLength <= OVERLAP_EPS) {
            return;
        }
        float mx = (xMin + xMax) * 0.5f;
        float my = (yMin + yMax) * 0.5f;
        float mz = (zMin + zMax) * 0.5f;

        if (xMax >= 1.0f - FACE_EPS && visible(cells, exact, cx + 1, cy, cz)) {
            contribute(cell, samplers, geom.cube, CubeFace.EAST,
                    1.0f, my, mz, yLength * zLength, model, modelFile, imageStore);
        }
        if (xMin <= FACE_EPS && visible(cells, exact, cx - 1, cy, cz)) {
            contribute(cell, samplers, geom.cube, CubeFace.WEST,
                    0.0f, my, mz, yLength * zLength, model, modelFile, imageStore);
        }
        if (yMax >= 1.0f - FACE_EPS && visible(cells, exact, cx, cy + 1, cz)) {
            contribute(cell, samplers, geom.cube, CubeFace.UP,
                    mx, 1.0f, mz, xLength * zLength, model, modelFile, imageStore);
        }
        if (yMin <= FACE_EPS && visible(cells, exact, cx, cy - 1, cz)) {
            contribute(cell, samplers, geom.cube, CubeFace.DOWN,
                    mx, 0.0f, mz, xLength * zLength, model, modelFile, imageStore);
        }
        if (zMax >= 1.0f - FACE_EPS && visible(cells, exact, cx, cy, cz + 1)) {
            contribute(cell, samplers, geom.cube, CubeFace.SOUTH,
                    mx, my, 1.0f, xLength * yLength, model, modelFile, imageStore);
        }
        if (zMin <= FACE_EPS && visible(cells, exact, cx, cy, cz - 1)) {
            contribute(cell, samplers, geom.cube, CubeFace.NORTH,
                    mx, my, 0.0f, xLength * yLength, model, modelFile, imageStore);
        }
    }

    /**
     * Whether the face plane gating this sample is visible: for exact geometry
     * a face is visible iff the voxel across its plane is unoccupied; for
     * approximated geometry every triggering face is treated as visible.
     */
    private static boolean visible(Map<Long, CellAccum> cells, boolean exact, int nx, int ny, int nz) {
        return !exact || !cells.containsKey(VoxelOccupancyCalculator.pack(nx, ny, nz));
    }

    /**
     * Adds one face's area-weighted color sample to a voxel accumulator. The
     * sample point is the center of the voxel-cube intersection projected onto
     * the face plane; the UV lookup runs through the shared {@link TexelSampler}
     * with the same {@code (fu, 1 - fv)} face-fraction convention the texel
     * plates position themselves with.
     */
    private static void contribute(
            CellAccum cell,
            Map<BakedFace, TexelSampler> samplers,
            BakedCube cube,
            CubeFace faceKey,
            float px,
            float py,
            float pz,
            float area,
            VirtualModel model,
            File modelFile,
            TextureImageStore imageStore
    ) {
        if (cell == null || area <= 0.0f) {
            return;
        }
        BakedFace face = cube.faces().get(faceKey);
        if (face == null) {
            return;
        }
        String textureName = face.textureName();
        if (textureName == null || textureName.isBlank()) {
            // Untextured faces render the pipeline's neutral surface (white
            // concrete) in the legacy renderer; the voxel inherits that tone.
            int neutral = TexelPalette.rgb(TexelPalette.NEUTRAL_INDEX);
            cell.addColor((neutral >>> 16) & 0xFF, (neutral >>> 8) & 0xFF, neutral & 0xFF, area);
            return;
        }
        TexelSampler sampler = samplers.get(face);
        if (sampler == null && !samplers.containsKey(face)) {
            TextureImageStore.TextureRaster raster = imageStore.raster(textureName, modelFile);
            sampler = raster != null ? new TexelSampler(face, raster, model.resolution()) : null;
            samplers.put(face, sampler);
        }
        if (sampler == null) {
            return;
        }
        float fu = component(faceKey.uAxis(), px, py, pz);
        float fv = 1.0f - component(faceKey.vAxis(), px, py, pz);
        int argb = sampler.sample(fu, fv);
        if (argb == 0) {
            return;
        }
        cell.addColor((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, area);
    }

    private static float component(int axis, float x, float y, float z) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    /**
     * Per-cube geometry precomputed once per bake and shared by both rasterization
     * passes: the cube's display matrix inverse for local-space probes, its
     * model-space AABB, the candidate lattice cell ranges, and — for axis-aligned
     * cubes — the translation/scale pair that resolves spans analytically with no
     * matrix arithmetic. {@code null} builds (degenerate scale, singular or
     * non-finite transform) are skipped like the matrix path's non-finite guards.
     */
    private static final class CubeGeom {

        final BakedCube cube;
        final Matrix4f inverse;
        final boolean analytic;
        final float tx, ty, tz, sx, sy, sz;
        final int x0, x1, y0, y1, z0, z1;

        private CubeGeom(
                BakedCube cube, Matrix4f inverse, boolean analytic,
                float tx, float ty, float tz, float sx, float sy, float sz,
                int x0, int x1, int y0, int y1, int z0, int z1) {
            this.cube = cube;
            this.inverse = inverse;
            this.analytic = analytic;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
            this.z0 = z0;
            this.z1 = z1;
        }

        static CubeGeom build(BakedCube cube, Vector3f shift) {
            Vector3f scale = cube.scale();
            boolean analytic = cube.isAxisAligned() && isIdentity(cube.rightRotation())
                    && Math.abs(scale.x) >= 1.0e-6f
                    && Math.abs(scale.y) >= 1.0e-6f
                    && Math.abs(scale.z) >= 1.0e-6f;

            Vector3f translation = cube.translation();
            float minX;
            float maxX;
            float minY;
            float maxY;
            float minZ;
            float maxZ;
            Matrix4f inverse = null;
            if (analytic) {
                minX = translation.x + Math.min(0.0f, scale.x);
                maxX = translation.x + Math.max(0.0f, scale.x);
                minY = translation.y + Math.min(0.0f, scale.y);
                maxY = translation.y + Math.max(0.0f, scale.y);
                minZ = translation.z + Math.min(0.0f, scale.z);
                maxZ = translation.z + Math.max(0.0f, scale.z);
            } else {
                Matrix4f matrix = new Matrix4f()
                        .translate(translation)
                        .rotate(cube.leftRotation())
                        .scale(scale)
                        .rotate(cube.rightRotation());
                inverse = new Matrix4f(matrix).invert();
                if (!inverse.isFinite()) {
                    return null;
                }
                minX = Float.MAX_VALUE;
                minY = Float.MAX_VALUE;
                minZ = Float.MAX_VALUE;
                maxX = -Float.MAX_VALUE;
                maxY = -Float.MAX_VALUE;
                maxZ = -Float.MAX_VALUE;
                Vector3f corner = new Vector3f();
                for (int c = 0; c < 8; c++) {
                    corner.set(
                            (c & 1) == 0 ? 0.0f : 1.0f,
                            (c & 2) == 0 ? 0.0f : 1.0f,
                            (c & 4) == 0 ? 0.0f : 1.0f);
                    matrix.transformPosition(corner);
                    if (!Float.isFinite(corner.x) || !Float.isFinite(corner.y)
                            || !Float.isFinite(corner.z)) {
                        return null;
                    }
                    minX = Math.min(minX, corner.x);
                    minY = Math.min(minY, corner.y);
                    minZ = Math.min(minZ, corner.z);
                    maxX = Math.max(maxX, corner.x);
                    maxY = Math.max(maxY, corner.y);
                    maxZ = Math.max(maxZ, corner.z);
                }
            }

            return new CubeGeom(
                    cube, inverse, analytic,
                    translation.x, translation.y, translation.z,
                    scale.x, scale.y, scale.z,
                    (int) Math.floor(minX - shift.x), (int) Math.ceil(maxX - shift.x),
                    (int) Math.floor(minY - shift.y), (int) Math.ceil(maxY - shift.y),
                    (int) Math.floor(minZ - shift.z), (int) Math.ceil(maxZ - shift.z));
        }
    }

    private static boolean isIdentity(org.joml.Quaternionf rotation) {
        return rotation == null
                || (rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z) <= 1.0e-4f;
    }

    private static VoxelModelBake emptyBake(RenderStrategySelector.Selection selection, long startNanos) {
        return new VoxelModelBake(selection.strategy(), selection.rationale(), List.of(),
                0, 0, 0, System.nanoTime() - startNanos, Map.of());
    }

    private static void warn(String message) {
        DebugLogger.warning("[VoxelBaking] " + message);
    }

    /** Area-weighted color accumulation for one occupied lattice cell. */
    private static final class CellAccum {

        private double red;
        private double green;
        private double blue;
        private double weight;
        private int lightEmission;

        void addColor(int red, int green, int blue, float area) {
            this.red += red * (double) area;
            this.green += green * (double) area;
            this.blue += blue * (double) area;
            this.weight += area;
        }
    }
}
