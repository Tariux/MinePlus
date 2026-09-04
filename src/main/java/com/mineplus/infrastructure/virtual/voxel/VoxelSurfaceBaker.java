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

public final class VoxelSurfaceBaker {

    private VoxelSurfaceBaker() {
    }

    private static final float OVERLAP_EPS = 1.0f / 1024.0f;
    private static final float FACE_EPS = 1.0f / 256.0f;
    private static final int MAX_PROBED_CELLS = 262_144;
    private static final float MATCH_TIE_TOLERANCE = 1.15f;

    private static final Comparator<Long> VOXEL_ORDER = (a, b) -> {
        int ya = VoxelOccupancyCalculator.unpackY(a);
        int yb = VoxelOccupancyCalculator.unpackY(b);
        if (ya != yb) return Integer.compare(ya, yb);
        int za = VoxelOccupancyCalculator.unpackZ(a);
        int zb = VoxelOccupancyCalculator.unpackZ(b);
        if (za != zb) return Integer.compare(za, zb);
        return Integer.compare(VoxelOccupancyCalculator.unpackX(a), VoxelOccupancyCalculator.unpackX(b));
    };

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
        VoxelRenderingSettings settings = voxelSettings == null ? VoxelRenderingSettings.defaults() : voxelSettings;

        if (model == null || imageStore == null) {
            return emptyBake(RenderStrategySelector.select(
                    model, meta, renderingSettings, settings, texelBake, originMode, false), startNanos);
        }

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

        List<BakedCube> cubes = model.cubes();
        CubeGeom[] geoms = new CubeGeom[cubes.size()];
        for (int i = 0; i < cubes.size(); i++) {
            geoms[i] = CubeGeom.build(cubes.get(i), shift);
        }
        Vector3f scratch = new Vector3f();
        float[] spans = new float[6];

        // Pass 1: Candidate Occupancy
        long probed = 0;
        boolean aborted = false;
        rasterize:
        for (CubeGeom geom : geoms) {
            if (geom == null) continue;
            for (int cy = geom.y0; cy < geom.y1; cy++) {
                for (int cz = geom.z0; cz < geom.z1; cz++) {
                    for (int cx = geom.x0; cx < geom.x1; cx++) {
                        if (++probed > MAX_PROBED_CELLS) {
                            aborted = true;
                            break rasterize;
                        }
                        if (!cellSpans(geom, cx, cy, cz, shift, scratch, spans)) continue;
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
            DebugLogger.warning("Model '" + model.name() + "': voxel bake aborted after probing "
                    + MAX_PROBED_CELLS + " cells; falling back to legacy pipeline.");
            return emptyBake(RenderStrategySelector.legacyStrategy(renderingSettings, texelBake,
                    "voxel bake aborted: model exceeds probed-cell guard"), startNanos);
        }

        // Pass 2: Texture Sampling & Cutout Carving
        for (CubeGeom geom : geoms) {
            if (geom == null) continue;
            for (int cy = geom.y0; cy < geom.y1; cy++) {
                for (int cz = geom.z0; cz < geom.z1; cz++) {
                    for (int cx = geom.x0; cx < geom.x1; cx++) {
                        CellAccum cell = cells.get(VoxelOccupancyCalculator.pack(cx, cy, cz));
                        if (cell == null) continue;
                        if (!cellSpans(geom, cx, cy, cz, shift, scratch, spans)) continue;
                        sampleCellFaces(cells, samplers, geom, cell, cx, cy, cz, spans,
                                model, modelFile, imageStore);
                    }
                }
            }
        }

        // Cutout Carving: Cells touching only transparent texels or untextured missing faces are hollowed out
        List<Long> carvedHoles = new ArrayList<>();
        for (Map.Entry<Long, CellAccum> entry : cells.entrySet()) {
            if (entry.getValue().weight <= 0.0) {
                carvedHoles.add(entry.getKey());
            }
        }
        for (Long hole : carvedHoles) {
            cells.remove(hole);
        }

        int occupiedVoxels = cells.size();

        // Pass 3: Exterior 3D BFS Flood-Fill
        int culledInteriorVoxels = applyExteriorFloodFill(cells);
        int surfaceVoxels = cells.size();

        // Pass 4: Palette Matching
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
                            cell.dominantFace,
                            previousInRow,
                            MATCH_TIE_TOLERANCE)
                    : TexelPalette.NEUTRAL_INDEX;

            indices[i] = index;
            emissions[i] = cell.lightEmission;
            previousInRow = index;
            paletteUsage.merge(index, 1, Integer::sum);
        }

        // Pass 5: 3D Greedy Meshing strictly obeying the stretchability rule
        List<VoxelModelBake.VoxelRun> runs = mergeRuns3D(keys, indices, emissions, shift);

        int maxDisplays = settings.effectiveMaxDisplays(meta);
        if (runs.size() > maxDisplays) {
            DebugLogger.warning("Model '" + model.name() + "': voxel reconstruction needs " + runs.size()
                    + " display runs (budget " + maxDisplays + "); keeping the legacy pipeline.");
            return emptyBake(RenderStrategySelector.legacyStrategy(renderingSettings, texelBake,
                    "voxel reconstruction exceeded display budget (" + runs.size() + " > " + maxDisplays + ")"), startNanos);
        }

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

    private static int applyExteriorFloodFill(Map<Long, CellAccum> cells) {
        if (cells.size() <= 1) return 0;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (long key : cells.keySet()) {
            int x = VoxelOccupancyCalculator.unpackX(key);
            int y = VoxelOccupancyCalculator.unpackY(key);
            int z = VoxelOccupancyCalculator.unpackZ(key);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }

        int originX = minX - 1;
        int originY = minY - 1;
        int originZ = minZ - 1;
        int sizeX = maxX - minX + 3;
        int sizeY = maxY - minY + 3;
        int sizeZ = maxZ - minZ + 3;
        int totalVolume = sizeX * sizeY * sizeZ;

        if (totalVolume <= 0 || totalVolume > 524_288) {
            return 0;
        }

        boolean[] visitedAir = new boolean[totalVolume];
        int[] queue = new int[totalVolume];
        int head = 0, tail = 0;

        queue[tail++] = 0;
        visitedAir[0] = true;

        int[] dx = { 1, -1, 0, 0, 0, 0 };
        int[] dy = { 0, 0, 1, -1, 0, 0 };
        int[] dz = { 0, 0, 0, 0, 1, -1 };

        while (head < tail) {
            int idx = queue[head++];
            int cz = idx / (sizeX * sizeY);
            int rem = idx % (sizeX * sizeY);
            int cy = rem / sizeX;
            int cx = rem % sizeX;

            for (int d = 0; d < 6; d++) {
                int nx = cx + dx[d];
                int ny = cy + dy[d];
                int nz = cz + dz[d];

                if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;

                int nIdx = (nz * sizeY + ny) * sizeX + nx;
                if (visitedAir[nIdx]) continue;

                int wx = originX + nx;
                int wy = originY + ny;
                int wz = originZ + nz;

                if (cells.containsKey(VoxelOccupancyCalculator.pack(wx, wy, wz))) {
                    continue;
                }

                visitedAir[nIdx] = true;
                queue[tail++] = nIdx;
            }
        }

        List<Long> culled = new ArrayList<>();
        for (long key : cells.keySet()) {
            int wx = VoxelOccupancyCalculator.unpackX(key);
            int wy = VoxelOccupancyCalculator.unpackY(key);
            int wz = VoxelOccupancyCalculator.unpackZ(key);

            int cx = wx - originX;
            int cy = wy - originY;
            int cz = wz - originZ;

            boolean visibleToAir = false;
            for (int d = 0; d < 6; d++) {
                int nx = cx + dx[d];
                int ny = cy + dy[d];
                int nz = cz + dz[d];
                if (nx >= 0 && nx < sizeX && ny >= 0 && ny < sizeY && nz >= 0 && nz < sizeZ) {
                    if (visitedAir[(nz * sizeY + ny) * sizeX + nx]) {
                        visibleToAir = true;
                        break;
                    }
                }
            }

            if (!visibleToAir) {
                culled.add(key);
            }
        }

        for (long key : culled) {
            cells.remove(key);
        }
        return culled.size();
    }

    /**
     * 3D Meshing: Merges X x Z x Y, but ONLY for stretchable (flat concrete) materials.
     * Non-stretchable detailed materials stay strict 1x1x1.
     */
    private static List<VoxelModelBake.VoxelRun> mergeRuns3D(
            long[] keys, int[] indices, int[] emissions, Vector3f shift) {
        if (keys.length == 0) return List.of();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : keys) {
            minX = Math.min(minX, VoxelOccupancyCalculator.unpackX(key));
            maxX = Math.max(maxX, VoxelOccupancyCalculator.unpackX(key));
            minZ = Math.min(minZ, VoxelOccupancyCalculator.unpackZ(key));
            maxZ = Math.max(maxZ, VoxelOccupancyCalculator.unpackZ(key));
        }

        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;

        List<VoxelModelBake.VoxelRun> flatRuns = new ArrayList<>();
        int[] grid = new int[spanX * spanZ];
        boolean[] visited = new boolean[spanX * spanZ];
        int i = 0;

        while (i < keys.length) {
            int y = VoxelOccupancyCalculator.unpackY(keys[i]);
            int levelEnd = i;
            while (levelEnd < keys.length && VoxelOccupancyCalculator.unpackY(keys[levelEnd]) == y) {
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
                    if (key < 0 || visited[origin]) continue;

                    int paletteIndex = key >> 4;
                    int lightEmission = key & 0xF;

                    // Non-stretchable materials NEVER merge: remain 1x1x1
                    if (!TexelPalette.isStretchable(paletteIndex)) {
                        flatRuns.add(new VoxelModelBake.VoxelRun(
                                minX + x + shift.x, y + shift.y, minZ + z + shift.z,
                                1, 1, 1, paletteIndex, lightEmission));
                        visited[origin] = true;
                        continue;
                    }

                    int runWidth = 1;
                    while (x + runWidth < spanX && !visited[origin + runWidth] && grid[origin + runWidth] == key) {
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

                    flatRuns.add(new VoxelModelBake.VoxelRun(
                            minX + x + shift.x, y + shift.y, minZ + z + shift.z,
                            runWidth, 1, runDepth, paletteIndex, lightEmission));
                }
            }
            i = levelEnd;
        }

        // Vertical consolidation (Y-axis) - ONLY for stretchable flat materials
        List<VoxelModelBake.VoxelRun> finalRuns = new ArrayList<>();
        boolean[] merged = new boolean[flatRuns.size()];

        for (int a = 0; a < flatRuns.size(); a++) {
            if (merged[a]) continue;
            VoxelModelBake.VoxelRun current = flatRuns.get(a);
            int heightY = current.heightY();

            if (TexelPalette.isStretchable(current.paletteIndex())) {
                for (int b = a + 1; b < flatRuns.size(); b++) {
                    if (merged[b]) continue;
                    VoxelModelBake.VoxelRun next = flatRuns.get(b);

                    if (Math.abs(next.x() - current.x()) < 1e-4f
                            && Math.abs(next.z() - current.z()) < 1e-4f
                            && Math.abs(next.y() - (current.y() + heightY)) < 1e-4f
                            && next.lengthX() == current.lengthX()
                            && next.widthZ() == current.widthZ()
                            && next.paletteIndex() == current.paletteIndex()
                            && next.lightEmission() == current.lightEmission()) {
                        heightY += next.heightY();
                        merged[b] = true;
                    }
                }
            }

            finalRuns.add(new VoxelModelBake.VoxelRun(
                    current.x(), current.y(), current.z(),
                    current.lengthX(), heightY, current.widthZ(),
                    current.paletteIndex(), current.lightEmission()));
        }

        return finalRuns;
    }

    private static boolean cellSpans(
            CubeGeom geom, int cx, int cy, int cz, Vector3f shift, Vector3f scratch, float[] out) {
        if (geom.analytic) {
            out[0] = clamp01((cx + shift.x - geom.tx) / geom.sx);
            out[1] = clamp01((cx + 1 + shift.x - geom.tx) / geom.sx);
            out[2] = clamp01((cy + shift.y - geom.ty) / geom.sy);
            out[3] = clamp01((cy + 1 + shift.y - geom.ty) / geom.sy);
            out[4] = clamp01((cz + shift.z - geom.tz) / geom.sz);
            out[5] = clamp01((cz + 1 + shift.z - geom.tz) / geom.sz);
            if (out[0] > out[1]) { float t = out[0]; out[0] = out[1]; out[1] = t; }
            if (out[2] > out[3]) { float t = out[2]; out[2] = out[3]; out[3] = t; }
            if (out[4] > out[5]) { float t = out[4]; out[4] = out[5]; out[5] = t; }
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
            if (!Float.isFinite(scratch.x) || !Float.isFinite(scratch.y) || !Float.isFinite(scratch.z)) {
                return false;
            }
            minX = Math.min(minX, scratch.x); maxX = Math.max(maxX, scratch.x);
            minY = Math.min(minY, scratch.y); maxY = Math.max(maxY, scratch.y);
            minZ = Math.min(minZ, scratch.z); maxZ = Math.max(maxZ, scratch.z);
        }
        out[0] = Math.max(minX, 0.0f); out[1] = Math.min(maxX, 1.0f);
        out[2] = Math.max(minY, 0.0f); out[3] = Math.min(maxY, 1.0f);
        out[4] = Math.max(minZ, 0.0f); out[5] = Math.min(maxZ, 1.0f);
        return true;
    }

    private static void sampleCellFaces(
            Map<Long, CellAccum> cells, Map<BakedFace, TexelSampler> samplers,
            CubeGeom geom, CellAccum cell, int cx, int cy, int cz, float[] spans,
            VirtualModel model, File modelFile, TextureImageStore imageStore) {
        float xMin = spans[0], xMax = spans[1];
        float yMin = spans[2], yMax = spans[3];
        float zMin = spans[4], zMax = spans[5];
        float xLength = xMax - xMin, yLength = yMax - yMin, zLength = zMax - zMin;

        if (xLength <= OVERLAP_EPS || yLength <= OVERLAP_EPS || zLength <= OVERLAP_EPS) return;

        float mx = (xMin + xMax) * 0.5f;
        float my = (yMin + yMax) * 0.5f;
        float mz = (zMin + zMax) * 0.5f;

        if (xMax >= 1.0f - FACE_EPS) contribute(cell, samplers, geom.cube, CubeFace.EAST, 1.0f, my, mz, yLength * zLength, model, modelFile, imageStore);
        if (xMin <= FACE_EPS)        contribute(cell, samplers, geom.cube, CubeFace.WEST, 0.0f, my, mz, yLength * zLength, model, modelFile, imageStore);
        if (yMax >= 1.0f - FACE_EPS) contribute(cell, samplers, geom.cube, CubeFace.UP, mx, 1.0f, mz, xLength * zLength, model, modelFile, imageStore);
        if (yMin <= FACE_EPS)        contribute(cell, samplers, geom.cube, CubeFace.DOWN, mx, 0.0f, mz, xLength * zLength, model, modelFile, imageStore);
        if (zMax >= 1.0f - FACE_EPS) contribute(cell, samplers, geom.cube, CubeFace.SOUTH, mx, my, 1.0f, xLength * yLength, model, modelFile, imageStore);
        if (zMin <= FACE_EPS)        contribute(cell, samplers, geom.cube, CubeFace.NORTH, mx, my, 0.0f, xLength * yLength, model, modelFile, imageStore);
    }

    private static void contribute(
            CellAccum cell, Map<BakedFace, TexelSampler> samplers, BakedCube cube, CubeFace faceKey,
            float px, float py, float pz, float area, VirtualModel model, File modelFile, TextureImageStore imageStore) {
        if (cell == null || area <= 0.0f) return;
        BakedFace face = cube.faces().get(faceKey);
        // If face is absent, it represents a hollow void: do not contribute color
        if (face == null) return;

        String textureName = face.textureName();
        if (textureName == null || textureName.isBlank()) return;

        TexelSampler sampler = samplers.computeIfAbsent(face, f -> {
            TextureImageStore.TextureRaster raster = imageStore.raster(textureName, modelFile);
            return raster != null ? new TexelSampler(f, raster, model.resolution()) : null;
        });

        if (sampler == null) return;

        float fu = component(faceKey.uAxis(), px, py, pz);
        float fv = 1.0f - component(faceKey.vAxis(), px, py, pz);
        int argb = sampler.sample(fu, fv);

        if (argb == 0) return; // Cutout transparent pixel: hollow carve
        cell.addColor((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, area, faceKey);
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

    private static final class CubeGeom {
        final BakedCube cube;
        final Matrix4f inverse;
        final boolean analytic;
        final float tx, ty, tz, sx, sy, sz;
        final int x0, x1, y0, y1, z0, z1;

        private CubeGeom(BakedCube cube, Matrix4f inverse, boolean analytic,
                         float tx, float ty, float tz, float sx, float sy, float sz,
                         int x0, int x1, int y0, int y1, int z0, int z1) {
            this.cube = cube; this.inverse = inverse; this.analytic = analytic;
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.x0 = x0; this.x1 = x1; this.y0 = y0; this.y1 = y1; this.z0 = z0; this.z1 = z1;
        }

        static CubeGeom build(BakedCube cube, Vector3f shift) {
            Vector3f scale = cube.scale();
            boolean analytic = cube.isAxisAligned() && isIdentity(cube.rightRotation())
                    && Math.abs(scale.x) >= 1e-6f && Math.abs(scale.y) >= 1e-6f && Math.abs(scale.z) >= 1e-6f;

            Vector3f translation = cube.translation();
            float minX, maxX, minY, maxY, minZ, maxZ;
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
                if (!inverse.isFinite()) return null;

                minX = minY = minZ = Float.MAX_VALUE;
                maxX = maxY = maxZ = -Float.MAX_VALUE;
                Vector3f corner = new Vector3f();
                for (int c = 0; c < 8; c++) {
                    corner.set((c & 1) == 0 ? 0.0f : 1.0f, (c & 2) == 0 ? 0.0f : 1.0f, (c & 4) == 0 ? 0.0f : 1.0f);
                    matrix.transformPosition(corner);
                    if (!Float.isFinite(corner.x) || !Float.isFinite(corner.y) || !Float.isFinite(corner.z)) return null;
                    minX = Math.min(minX, corner.x); maxX = Math.max(maxX, corner.x);
                    minY = Math.min(minY, corner.y); maxY = Math.max(maxY, corner.y);
                    minZ = Math.min(minZ, corner.z); maxZ = Math.max(maxZ, corner.z);
                }
            }

            return new CubeGeom(cube, inverse, analytic,
                    translation.x, translation.y, translation.z,
                    scale.x, scale.y, scale.z,
                    (int) Math.floor(minX - shift.x), (int) Math.ceil(maxX - shift.x),
                    (int) Math.floor(minY - shift.y), (int) Math.ceil(maxY - shift.y),
                    (int) Math.floor(minZ - shift.z), (int) Math.ceil(maxZ - shift.z));
        }
    }

    private static boolean isIdentity(org.joml.Quaternionf rotation) {
        return rotation == null || (rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z) <= 1.0e-4f;
    }

    private static VoxelModelBake emptyBake(RenderStrategySelector.Selection selection, long startNanos) {
        return new VoxelModelBake(selection.strategy(), selection.rationale(), List.of(),
                0, 0, 0, System.nanoTime() - startNanos, Map.of());
    }

    private static final class CellAccum {
        private double red;
        private double green;
        private double blue;
        private double weight;
        private int lightEmission;
        private CubeFace dominantFace = CubeFace.UP;
        private float maxFaceArea = 0.0f;

        void addColor(int red, int green, int blue, float area, CubeFace face) {
            this.red += red * (double) area;
            this.green += green * (double) area;
            this.blue += blue * (double) area;
            this.weight += area;
            if (area > maxFaceArea) {
                maxFaceArea = area;
                dominantFace = face;
            }
        }
    }
}