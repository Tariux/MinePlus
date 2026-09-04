package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.FaceUvAnalyzer;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class TexelSurfaceBaker {

    private TexelSurfaceBaker() {
    }

    private static final float PLATE_SURFACE_OFFSET_BLOCKS = 1.0f / 256.0f;
    private static final float OCCLUSION_SHRINK_BLOCKS = 0.01f / 16.0f;
    private static final float CORNER_PROBE_INSET = 0.05f;

    private static final int TRANSPARENT = -1;
    private static final int OCCLUDED = -2;

    public static TexelBakeResult bakeModel(
            VirtualModel model,
            ModelMeta meta,
            File modelFile,
            TextureImageStore imageStore,
            TexelBakingSettings settings
    ) {
        ModelMeta.TexelMode mode = settings.effectiveMode(meta);
        ModelMeta.TexelDetail detail = settings.effectiveDetail(meta);
        if (model == null || mode == ModelMeta.TexelMode.OFF || imageStore == null) {
            return TexelBakeResult.disabled(mode, detail, settings, model == null ? 0 : model.cubes().size());
        }
        int maxPlatesPerFace = settings.effectiveMaxPlatesPerFace(meta);
        int maxPlatesPerInstance = settings.effectiveMaxPlatesPerInstance(meta);

        long startNanos = System.nanoTime();
        OccluderSet occluders = OccluderSet.build(model.cubes());
        Map<BakedFace, TexelSampler> samplers = new java.util.HashMap<>();
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans = new ArrayList<>(model.cubes().size());
        int facesBaked = 0;
        int facesTotal = 0;
        int totalPlates = 0;
        int maxPlatesOnFace = 0;
        int faceBudgetFallbacks = 0;
        int instanceBudgetFallbacks = 0;
        int occludedCells = 0;
        int runningPlates = 0;
        Map<String, Integer> gridHistogram = new LinkedHashMap<>();
        Map<Integer, Integer> paletteUsage = new LinkedHashMap<>();

        int cubeIndex = 0;
        for (BakedCube cube : model.cubes()) {
            Map<CubeFace, TexelSurfacePlan> facePlans = new EnumMap<>(CubeFace.class);
            for (CubeFace faceKey : CubeFace.values()) {
                BakedFace face = cube.faces().get(faceKey);
                if (face == null) {
                    continue;
                }
                facesTotal++;
                if (face.textureName() == null || face.textureName().isBlank()) {
                    continue;
                }

                TextureImageStore.TextureRaster raster = imageStore.raster(face.textureName(), modelFile);
                FaceUvAnalyzer.UvPlan plan = FaceUvAnalyzer.analyze(face, mode, raster != null);
                if (plan.strategy() != FaceUvAnalyzer.UvPlan.Strategy.TEXEL) {
                    continue;
                }

                TexelSurfacePlan baked = bakeFace(
                        face, faceKey, cube, model, raster, detail, settings, occluders,
                        cubeIndex, samplers, modelFile, imageStore);
                occludedCells += baked.occludedCells();
                if (baked.plateCount() > maxPlatesPerFace) {
                    faceBudgetFallbacks++;
                    continue;
                }
                if (runningPlates + baked.plateCount() > maxPlatesPerInstance) {
                    instanceBudgetFallbacks++;
                    continue;
                }

                runningPlates += baked.plateCount();
                facesBaked++;
                totalPlates += baked.plateCount();
                maxPlatesOnFace = Math.max(maxPlatesOnFace, baked.plateCount());
                gridHistogram.merge(baked.gridWidth() + "x" + baked.gridHeight(), 1, Integer::sum);
                for (TexelSurfacePlan.Rect rect : baked.plates()) {
                    paletteUsage.merge(rect.paletteIndex(), rect.width() * rect.height(), Integer::sum);
                }
                facePlans.put(faceKey, baked);
            }
            cubePlans.add(facePlans);
            cubeIndex++;
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        return new TexelBakeResult(
                true, mode, detail, cubePlans, facesBaked, facesTotal, totalPlates,
                maxPlatesOnFace, faceBudgetFallbacks, instanceBudgetFallbacks, elapsedNanos,
                gridHistogram, paletteUsage, maxPlatesPerFace, maxPlatesPerInstance, occludedCells
        );
    }

    private static TexelSurfacePlan bakeFace(
            BakedFace face,
            CubeFace faceKey,
            BakedCube cube,
            VirtualModel model,
            TextureImageStore.TextureRaster raster,
            ModelMeta.TexelDetail detail,
            TexelBakingSettings settings,
            OccluderSet occluders,
            int cubeIndex,
            Map<BakedFace, TexelSampler> samplers,
            File modelFile,
            TextureImageStore imageStore
    ) {
        float[] pixelSize = FaceUvAnalyzer.facePixelSize(faceKey, cube);
        int gridWidth = clampAxis(Math.round(pixelSize[0]), settings.maxGridEdge());
        int gridHeight = clampAxis(Math.round(pixelSize[1]), settings.maxGridEdge());

        SamplingContext context = new SamplingContext(
                new TexelSampler(face, raster, model.resolution()),
                gridWidth,
                gridHeight,
                detail.sampleCount()
        );
        Matrix4f ownMatrix = occluders.matrix(cubeIndex);

        int[] grid = new int[gridWidth * gridHeight];
        int occludedCells = 0;
        Vector3f unit = new Vector3f();
        Vector3f world = new Vector3f();
        Vector3f local = new Vector3f();

        for (int row = 0; row < gridHeight; row++) {
            int previous = -1;
            for (int column = 0; column < gridWidth; column++) {
                if (cellOccluded(occluders, ownMatrix, cubeIndex, faceKey, cube,
                        column, row, gridWidth, gridHeight, unit, world, local,
                        samplers, model, modelFile, imageStore)) {
                    grid[row * gridWidth + column] = OCCLUDED;
                    occludedCells++;
                    continue;
                }
                int index = sampleTexel(context, column, row, previous, faceKey);
                grid[row * gridWidth + column] = index;
                if (index >= 0) {
                    previous = index;
                }
            }
        }

        // Only infill single stray isolated pixels. Cutout grates, slats and intentional holes are preserved!
        cleanStraySinglePixelsOnly(grid, gridWidth, gridHeight);

        int cutoutCells = 0;
        for (int value : grid) {
            if (value == TRANSPARENT) {
                cutoutCells++;
            }
        }

        List<TexelSurfacePlan.Rect> rects = TexelMerge.merge(grid, gridWidth, gridHeight, 0.04f); // 0.04 Oklab tolerance
        int[] areaByIndex = paletteAreas(rects);
        int dominantIndex = -1;
        int dominantArea = 0;
        for (int i = 0; i < areaByIndex.length; i++) {
            if (areaByIndex[i] > dominantArea) {
                dominantArea = areaByIndex[i];
                dominantIndex = i;
            }
        }
        return new TexelSurfacePlan(gridWidth, gridHeight, rects, dominantIndex, dominantArea, occludedCells, cutoutCells);
    }

    private static boolean cellOccluded(
            OccluderSet occluders, Matrix4f ownMatrix, int ownIndex, CubeFace faceKey, BakedCube cube,
            int column, int row, int gridWidth, int gridHeight, Vector3f unit, Vector3f world, Vector3f local,
            Map<BakedFace, TexelSampler> samplers, VirtualModel model, File modelFile, TextureImageStore imageStore) {
        if (!occluders.hasOccluders()) return false;

        if (!pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube,
                (column + 0.5f) / gridWidth, 1.0f - (row + 0.5f) / gridHeight,
                unit, world, local, samplers, model, modelFile, imageStore)) {
            return false;
        }

        float u0 = (column + CORNER_PROBE_INSET) / gridWidth;
        float u1 = (column + 1.0f - CORNER_PROBE_INSET) / gridWidth;
        float v0 = 1.0f - (row + CORNER_PROBE_INSET) / gridHeight;
        float v1 = 1.0f - (row + 1.0f - CORNER_PROBE_INSET) / gridHeight;

        return pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u0, v0, unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u1, v0, unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u0, v1, unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u1, v1, unit, world, local, samplers, model, modelFile, imageStore);
    }

    private static boolean pointOccluded(
            OccluderSet occluders, Matrix4f ownMatrix, int ownIndex, CubeFace faceKey, BakedCube cube,
            float cu, float cv, Vector3f unit, Vector3f world, Vector3f local,
            Map<BakedFace, TexelSampler> samplers, VirtualModel model, File modelFile, TextureImageStore imageStore) {
        int normalAxis = faceKey.normalAxis();
        float normalScale = Math.max(Math.abs(cube.scale().get(normalAxis)), 1.0e-6f);
        float surfaceLocal = PLATE_SURFACE_OFFSET_BLOCKS / normalScale;

        unit.setComponent(faceKey.uAxis(), cu);
        unit.setComponent(faceKey.vAxis(), cv);
        unit.setComponent(normalAxis, faceKey.positiveNormal() ? 1.0f + surfaceLocal : -surfaceLocal);
        ownMatrix.transformPosition(unit, world);

        for (int i = 0; i < occluders.count(); i++) {
            if (i == ownIndex || !occluders.usable(i)) continue;
            occluders.inverse(i).transformPosition(world, local);
            Vector3f shrink = occluders.shrink(i);
            if (local.x > shrink.x && local.x < 1.0f - shrink.x
                    && local.y > shrink.y && local.y < 1.0f - shrink.y
                    && local.z > shrink.z && local.z < 1.0f - shrink.z) {
                if (seeThrough(occluders.cube(i), faceKey, local, samplers, model, modelFile, imageStore)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean seeThrough(
            BakedCube occluder, CubeFace faceKey, Vector3f local,
            Map<BakedFace, TexelSampler> samplers, VirtualModel model, File modelFile, TextureImageStore imageStore) {
        BakedFace face = occluder.faces().get(faceKey);
        if (face == null || face.textureName() == null || face.textureName().isBlank()) return false;

        TexelSampler sampler = samplers.computeIfAbsent(face, f -> {
            TextureImageStore.TextureRaster raster = imageStore.raster(face.textureName(), modelFile);
            return raster != null ? new TexelSampler(face, raster, model.resolution()) : null;
        });

        if (sampler == null) return false;
        float fu = component(faceKey.uAxis(), local.x, local.y, local.z);
        float fv = 1.0f - component(faceKey.vAxis(), local.x, local.y, local.z);
        return sampler.sample(fu, fv) == 0;
    }

    private static float component(int axis, float x, float y, float z) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }

    /**
     * Clean ONLY strictly isolated 1x1 single transparent noise pixels, keeping slats and holes intact.
     */
    private static void cleanStraySinglePixelsOnly(int[] grid, int width, int height) {
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                if (grid[idx] != TRANSPARENT) continue;
                // If surrounded on all 4 sides by non-transparent pixels, it's single pixel noise
                int up = grid[(y - 1) * width + x];
                int down = grid[(y + 1) * width + x];
                int left = grid[y * width + (x - 1)];
                int right = grid[y * width + (x + 1)];
                if (up >= 0 && down >= 0 && left >= 0 && right >= 0 && up == down && left == right && up == left) {
                    grid[idx] = up;
                }
            }
        }
    }

    private static int[] paletteAreas(List<TexelSurfacePlan.Rect> rects) {
        int[] areaByIndex = new int[TexelPalette.size()];
        for (TexelSurfacePlan.Rect rect : rects) {
            areaByIndex[rect.paletteIndex()] += rect.width() * rect.height();
        }
        return areaByIndex;
    }

    private static int sampleTexel(SamplingContext context, int column, int row, int previousIndex, CubeFace faceKey) {
        double redSum = 0, greenSum = 0, blueSum = 0, weightSum = 0;
        int coveredSamples = 0;
        int totalSamples = context.samples() * context.samples();

        for (int sy = 0; sy < context.samples(); sy++) {
            for (int sx = 0; sx < context.samples(); sx++) {
                float fu = (column + (sx + 0.5f) / context.samples()) / context.gridWidth();
                float fv = (row + (sy + 0.5f) / context.samples()) / context.gridHeight();

                int argb = context.sampler().sample(fu, fv);
                if (argb != 0) {
                    float weight = ((argb >>> 24) & 0xFF) / 255.0f;
                    redSum += ((argb >>> 16) & 0xFF) * (double) weight;
                    greenSum += ((argb >>> 8) & 0xFF) * (double) weight;
                    blueSum += (argb & 0xFF) * (double) weight;
                    weightSum += weight;
                    coveredSamples++;
                }
            }
        }

        if (coveredSamples * 2 < totalSamples || weightSum <= 0.0) {
            return TRANSPARENT;
        }

        return TexelPalette.match(
                (int) Math.round(redSum / weightSum),
                (int) Math.round(greenSum / weightSum),
                (int) Math.round(blueSum / weightSum),
                faceKey,
                previousIndex,
                1.15f
        );
    }

    private static final class OccluderSet {
        private final BakedCube[] cubes;
        private final Matrix4f[] matrices;
        private final Matrix4f[] inverses;
        private final Vector3f[] shrinks;
        private final boolean[] usable;
        private final boolean hasOccluders;

        private OccluderSet(BakedCube[] cubes, Matrix4f[] matrices, Matrix4f[] inverses,
                            Vector3f[] shrinks, boolean[] usable, boolean hasOccluders) {
            this.cubes = cubes;
            this.matrices = matrices;
            this.inverses = inverses;
            this.shrinks = shrinks;
            this.usable = usable;
            this.hasOccluders = hasOccluders;
        }

        static OccluderSet build(List<BakedCube> cubes) {
            int count = cubes == null ? 0 : cubes.size();
            BakedCube[] cubeArray = cubes == null ? new BakedCube[0] : cubes.toArray(new BakedCube[0]);
            Matrix4f[] matrices = new Matrix4f[count];
            Matrix4f[] inverses = new Matrix4f[count];
            Vector3f[] shrinks = new Vector3f[count];
            boolean[] usable = new boolean[count];
            boolean anyUsable = false;

            for (int i = 0; i < count; i++) {
                BakedCube cube = cubes.get(i);
                Matrix4f matrix = new Matrix4f()
                        .translate(cube.translation())
                        .rotate(cube.leftRotation())
                        .scale(cube.scale())
                        .rotate(cube.rightRotation());
                matrices[i] = matrix;
                Vector3f scale = cube.scale();
                boolean degenerate = Math.abs(scale.x) < 1.0e-6f || Math.abs(scale.y) < 1.0e-6f || Math.abs(scale.z) < 1.0e-6f;
                usable[i] = !degenerate;
                anyUsable |= !degenerate;
                inverses[i] = degenerate ? null : new Matrix4f(matrix).invert();
                shrinks[i] = new Vector3f(
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.x), 1.0e-6f),
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.y), 1.0e-6f),
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.z), 1.0e-6f));
            }
            return new OccluderSet(cubeArray, matrices, inverses, shrinks, usable, anyUsable && count > 1);
        }

        boolean hasOccluders() { return hasOccluders; }
        int count() { return matrices.length; }
        BakedCube cube(int index) { return cubes[index]; }
        Matrix4f matrix(int index) { return matrices[index]; }
        Matrix4f inverse(int index) { return inverses[index]; }
        Vector3f shrink(int index) { return shrinks[index]; }
        boolean usable(int index) { return usable[index]; }
    }

    private record SamplingContext(TexelSampler sampler, int gridWidth, int gridHeight, int samples) {}

    private static int clampAxis(int value, int maxEdge) {
        return Math.max(1, Math.min(maxEdge, value));
    }
}