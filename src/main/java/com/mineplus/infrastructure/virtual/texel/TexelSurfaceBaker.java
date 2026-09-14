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
import java.util.HashMap;
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
    private static final float MERGE_OKLAB_TOLERANCE = 0.04f;
    private static final int SAMPLE_GRID_CACHE_MAX = 512;

    private static final int TRANSPARENT = -1;
    private static final int OCCLUDED = -2;

    /** One face rejected by a plate budget; the emitter may tint it with the cube's dominant palette entry. */
    private record BudgetFallbackFace(int cubeIndex, CubeFace face) {
    }

    /** One face's baked plan plus whether its sampling pass was served from the reuse cache. */
    private record BakeOutcome(TexelSurfacePlan plan, boolean reused) {
    }

    /**
     * Position-independent identity of a face's sampling pass: two faces that
     * agree on texture, UV window, in-plane rotation, grid size and sample count
     * sample identical pixels, so their grids can be shared. Occlusion is
     * deliberately excluded — it depends on world position and is applied per
     * face after the cached grid is copied.
     */
    private record FaceSignature(
            String texture,
            CubeFace face,
            float u1,
            float u2,
            float v1,
            float v2,
            float rotation,
            int gridWidth,
            int gridHeight,
            int samples
    ) {
    }

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
        int textureWidth = model.resolution().width();
        int textureHeight = model.resolution().height();

        long startNanos = System.nanoTime();
        OccluderSet occluders = OccluderSet.build(model.cubes());
        Map<BakedFace, TexelSampler> samplers = new HashMap<>();
        Map<FaceSignature, int[]> sampleGridCache = settings.reuseSymmetricFaces() ? new HashMap<>() : null;
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans = new ArrayList<>(model.cubes().size());
        List<BudgetFallbackFace> budgetFallbackFaces = new ArrayList<>();
        int facesBaked = 0;
        int facesTotal = 0;
        int totalPlates = 0;
        int maxPlatesOnFace = 0;
        int faceBudgetFallbacks = 0;
        int instanceBudgetFallbacks = 0;
        int simplifiedFallbacks = 0;
        int reusedFaceBakes = 0;
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
                FaceUvAnalyzer.UvPlan plan = FaceUvAnalyzer.analyze(
                        face, mode, raster != null, textureWidth, textureHeight);
                if (plan.strategy() != FaceUvAnalyzer.UvPlan.Strategy.TEXEL) {
                    continue;
                }

                BakeOutcome outcome = bakeFace(
                        face, faceKey, cube, model, raster, detail, settings, occluders,
                        cubeIndex, samplers, modelFile, imageStore, sampleGridCache);
                TexelSurfacePlan baked = outcome.plan();
                if (outcome.reused()) {
                    reusedFaceBakes++;
                }
                occludedCells += baked.occludedCells();

                // Adaptive budgeting only tightens the *default* ceiling; an explicit
                // per-model maxTexelPlatesPerFace is an opt-in and is never reduced.
                boolean metaFaceOverride = meta != null && meta.maxTexelPlatesPerFace() != null;
                int faceCeiling = metaFaceOverride
                        ? maxPlatesPerFace
                        : settings.effectiveFaceCeiling(baked.gridWidth(), baked.gridHeight(), maxPlatesPerFace);
                int plates = baked.plateCount();
                boolean faceOver = plates > faceCeiling;
                boolean instanceOver = !faceOver && runningPlates + plates > maxPlatesPerInstance;

                if (faceOver || instanceOver) {
                    TexelSurfacePlan simplified = settings.budgetFallbackToSimpleColor()
                            ? simplifyToDominant(baked) : null;
                    if (simplified != null) {
                        if (faceOver) {
                            faceBudgetFallbacks++;
                        } else {
                            instanceBudgetFallbacks++;
                        }
                        simplifiedFallbacks++;
                        baked = simplified;
                        plates = 1;
                    } else {
                        if (faceOver) {
                            faceBudgetFallbacks++;
                        } else {
                            instanceBudgetFallbacks++;
                        }
                        budgetFallbackFaces.add(new BudgetFallbackFace(cubeIndex, faceKey));
                        continue;
                    }
                }

                runningPlates += plates;
                facesBaked++;
                totalPlates += plates;
                maxPlatesOnFace = Math.max(maxPlatesOnFace, plates);
                gridHistogram.merge(baked.gridWidth() + "x" + baked.gridHeight(), 1, Integer::sum);
                for (TexelSurfacePlan.Rect rect : baked.plates()) {
                    paletteUsage.merge(rect.paletteIndex(), rect.width() * rect.height(), Integer::sum);
                }
                facePlans.put(faceKey, baked);
            }
            cubePlans.add(facePlans);
            cubeIndex++;
        }

        // Budget-fallback tints: a face that fell back from texel baking and whose
        // texture resolves to no vanilla material is plated with its cube's dominant
        // baked palette entry instead of the resolver's concrete-white fallback —
        // partially baked models degrade to a flat local tone, never white.
        List<Map<CubeFace, Integer>> cubeFallbackTints = computeFallbackTints(cubePlans, budgetFallbackFaces);

        if (simplifiedFallbacks > 0 || reusedFaceBakes > 0) {
            DebugLogger.debug("texel: " + model.name() + " simplified " + simplifiedFallbacks
                    + " over-budget face(s), reused " + reusedFaceBakes + " identical face bake(s).");
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        return new TexelBakeResult(
                true, mode, detail, cubePlans, facesBaked, facesTotal, totalPlates,
                maxPlatesOnFace, faceBudgetFallbacks, instanceBudgetFallbacks, simplifiedFallbacks,
                reusedFaceBakes, elapsedNanos,
                gridHistogram, paletteUsage, maxPlatesPerFace, maxPlatesPerInstance, occludedCells,
                cubeFallbackTints
        );
    }

    /**
     * Degrades an over-budget face to a single dominant-color plate covering the
     * whole face. Refused (returns null, preserving the legacy per-face fallback)
     * when the face has no baked color or has genuine cutout holes — a full-face
     * plate would z-block the see-through content behind them.
     */
    private static TexelSurfacePlan simplifyToDominant(TexelSurfacePlan plan) {
        if (plan.dominantPaletteIndex() < 0 || plan.dominantArea() <= 0 || plan.cutoutCells() > 0) {
            return null;
        }
        List<TexelSurfacePlan.Rect> single = List.of(new TexelSurfacePlan.Rect(
                0, 0, plan.gridWidth(), plan.gridHeight(), plan.dominantPaletteIndex()));
        return new TexelSurfacePlan(plan.gridWidth(), plan.gridHeight(), single,
                plan.dominantPaletteIndex(), plan.dominantArea(), plan.occludedCells(), 0);
    }

    /**
     * Assigns each budget-fallback face the dominant palette entry of its cube's
     * successfully baked faces (total plate area, argmax). Faces of cubes with no
     * baked faces keep the resolver's legacy behavior.
     */
    private static List<Map<CubeFace, Integer>> computeFallbackTints(
            List<Map<CubeFace, TexelSurfacePlan>> cubePlans,
            List<BudgetFallbackFace> budgetFallbackFaces
    ) {
        if (budgetFallbackFaces.isEmpty()) {
            return List.of();
        }
        Map<Integer, Map<CubeFace, Integer>> tintsByCube = new HashMap<>();
        for (BudgetFallbackFace fallback : budgetFallbackFaces) {
            if (fallback.cubeIndex() >= cubePlans.size()) {
                continue;
            }
            Map<CubeFace, TexelSurfacePlan> facePlans = cubePlans.get(fallback.cubeIndex());
            if (facePlans.isEmpty()) {
                continue; // nothing baked on this cube — no local tone available
            }
            int dominantIndex = dominantPaletteIndex(facePlans);
            if (dominantIndex >= 0) {
                tintsByCube
                        .computeIfAbsent(fallback.cubeIndex(), k -> new EnumMap<>(CubeFace.class))
                        .put(fallback.face(), dominantIndex);
            }
        }
        if (tintsByCube.isEmpty()) {
            return List.of();
        }
        List<Map<CubeFace, Integer>> result = new ArrayList<>(cubePlans.size());
        for (int i = 0; i < cubePlans.size(); i++) {
            Map<CubeFace, Integer> tints = tintsByCube.get(i);
            result.add(tints == null ? Map.of() : Map.copyOf(tints));
        }
        return result;
    }

    private static int dominantPaletteIndex(Map<CubeFace, TexelSurfacePlan> facePlans) {
        int[] areaByIndex = null;
        for (TexelSurfacePlan plan : facePlans.values()) {
            if (plan.plates().isEmpty()) {
                continue;
            }
            if (areaByIndex == null) {
                areaByIndex = new int[TexelPalette.size()];
            }
            for (TexelSurfacePlan.Rect rect : plan.plates()) {
                areaByIndex[rect.paletteIndex()] += rect.width() * rect.height();
            }
        }
        if (areaByIndex == null) {
            return -1;
        }
        int dominantIndex = -1;
        int dominantArea = 0;
        for (int i = 0; i < areaByIndex.length; i++) {
            if (areaByIndex[i] > dominantArea) {
                dominantArea = areaByIndex[i];
                dominantIndex = i;
            }
        }
        return dominantIndex;
    }

    private static BakeOutcome bakeFace(
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
            TextureImageStore imageStore,
            Map<FaceSignature, int[]> sampleGridCache
    ) {
        float[] pixelSize = FaceUvAnalyzer.facePixelSize(faceKey, cube);
        int gridWidth = clampAxis(Math.round(pixelSize[0]), settings.maxGridEdge());
        int gridHeight = clampAxis(Math.round(pixelSize[1]), settings.maxGridEdge());

        // Position-dependent occlusion mask, computed once and applied after
        // sampling so the sampling grid stays shareable between identical faces.
        boolean[] occludedMask = null;
        int occludedCells = 0;
        if (occluders.hasOccluders()) {
            occludedMask = new boolean[gridWidth * gridHeight];
            Matrix4f ownMatrix = occluders.matrix(cubeIndex);
            Vector3f unit = new Vector3f();
            Vector3f world = new Vector3f();
            Vector3f local = new Vector3f();
            for (int row = 0; row < gridHeight; row++) {
                for (int column = 0; column < gridWidth; column++) {
                    if (cellOccluded(occluders, ownMatrix, cubeIndex, faceKey, cube,
                            column, row, gridWidth, gridHeight, unit, world, local,
                            samplers, model, modelFile, imageStore)) {
                        occludedMask[row * gridWidth + column] = true;
                        occludedCells++;
                    }
                }
            }
        }

        FaceSignature signature = new FaceSignature(
                face.textureName(), faceKey, face.u1(), face.u2(), face.v1(), face.v2(),
                face.rotation(), gridWidth, gridHeight, detail.sampleCount());
        int[] cached = sampleGridCache != null ? sampleGridCache.get(signature) : null;
        boolean reused = cached != null;

        int[] grid;
        if (reused) {
            grid = cached.clone();
        } else {
            SamplingContext context = new SamplingContext(
                    new TexelSampler(face, raster, model.resolution()),
                    gridWidth,
                    gridHeight,
                    detail.sampleCount()
            );
            grid = new int[gridWidth * gridHeight];
            for (int row = 0; row < gridHeight; row++) {
                int previous = -1;
                for (int column = 0; column < gridWidth; column++) {
                    int index = sampleTexel(context, column, row, previous, faceKey);
                    grid[row * gridWidth + column] = index;
                    if (index >= 0) {
                        previous = index;
                    }
                }
            }
            // Only infill single stray isolated pixels. Cutout grates, slats and intentional holes are preserved!
            cleanStraySinglePixelsOnly(grid, gridWidth, gridHeight);
            if (sampleGridCache != null) {
                if (sampleGridCache.size() >= SAMPLE_GRID_CACHE_MAX) {
                    sampleGridCache.clear();
                }
                sampleGridCache.put(signature, grid.clone());
            }
        }

        if (occludedMask != null) {
            for (int i = 0; i < grid.length; i++) {
                if (occludedMask[i]) {
                    grid[i] = OCCLUDED;
                }
            }
        }

        int cutoutCells = 0;
        for (int value : grid) {
            if (value == TRANSPARENT) {
                cutoutCells++;
            }
        }

        List<TexelSurfacePlan.Rect> rects = settings.uniformAreaDetection()
                ? TexelUniformCoalescer.mergeWithUniformAreas(
                        grid, gridWidth, gridHeight,
                        settings.uniformAreaMinSize(), settings.uniformAreaOklabThreshold(), MERGE_OKLAB_TOLERANCE)
                : TexelMerge.merge(grid, gridWidth, gridHeight, MERGE_OKLAB_TOLERANCE);

        int[] areaByIndex = paletteAreas(rects);
        int dominantIndex = -1;
        int dominantArea = 0;
        for (int i = 0; i < areaByIndex.length; i++) {
            if (areaByIndex[i] > dominantArea) {
                dominantArea = areaByIndex[i];
                dominantIndex = i;
            }
        }
        return new BakeOutcome(new TexelSurfacePlan(
                gridWidth, gridHeight, rects, dominantIndex, dominantArea, occludedCells, cutoutCells), reused);
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
            // Cheap world-space bounding-box reject before the inverse transform:
            // a point outside an occluder's AABB cannot be inside its oriented box.
            if (occluders.aabbRejects(i, world)) continue;
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
        // Accumulate in LINEAR light with alpha coverage as weight: this is how
        // the client composites what it actually sees, so the palette match runs
        // against the perceptually-correct average instead of a gamma-space mean
        // (which over-weights dark samples and muddies half-covered texels).
        double redLinearSum = 0, greenLinearSum = 0, blueLinearSum = 0, weightSum = 0;
        int coveredSamples = 0;
        int totalSamples = context.samples() * context.samples();

        for (int sy = 0; sy < context.samples(); sy++) {
            for (int sx = 0; sx < context.samples(); sx++) {
                float fu = (column + (sx + 0.5f) / context.samples()) / context.gridWidth();
                float fv = (row + (sy + 0.5f) / context.samples()) / context.gridHeight();

                int argb = context.sampler().sample(fu, fv);
                if (argb != 0) {
                    float weight = ((argb >>> 24) & 0xFF) / 255.0f;
                    redLinearSum += TexelPalette.sRgbToLinear(((argb >>> 16) & 0xFF) / 255.0f) * weight;
                    greenLinearSum += TexelPalette.sRgbToLinear(((argb >>> 8) & 0xFF) / 255.0f) * weight;
                    blueLinearSum += TexelPalette.sRgbToLinear((argb & 0xFF) / 255.0f) * weight;
                    weightSum += weight;
                    coveredSamples++;
                }
            }
        }

        if (coveredSamples * 2 < totalSamples || weightSum <= 0.0) {
            return TRANSPARENT;
        }

        return TexelPalette.match(
                TexelPalette.srgbChannelFromLinear((float) (redLinearSum / weightSum)),
                TexelPalette.srgbChannelFromLinear((float) (greenLinearSum / weightSum)),
                TexelPalette.srgbChannelFromLinear((float) (blueLinearSum / weightSum)),
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
        /** Flattened model-space AABBs: {@code [i*3 + axis]} — cheap pre-filter for the OBB test. */
        private final float[] aabbMin;
        private final float[] aabbMax;
        private final boolean hasOccluders;

        private OccluderSet(BakedCube[] cubes, Matrix4f[] matrices, Matrix4f[] inverses,
                            Vector3f[] shrinks, boolean[] usable, float[] aabbMin, float[] aabbMax,
                            boolean hasOccluders) {
            this.cubes = cubes;
            this.matrices = matrices;
            this.inverses = inverses;
            this.shrinks = shrinks;
            this.usable = usable;
            this.aabbMin = aabbMin;
            this.aabbMax = aabbMax;
            this.hasOccluders = hasOccluders;
        }

        static OccluderSet build(List<BakedCube> cubes) {
            int count = cubes == null ? 0 : cubes.size();
            BakedCube[] cubeArray = cubes == null ? new BakedCube[0] : cubes.toArray(new BakedCube[0]);
            Matrix4f[] matrices = new Matrix4f[count];
            Matrix4f[] inverses = new Matrix4f[count];
            Vector3f[] shrinks = new Vector3f[count];
            boolean[] usable = new boolean[count];
            float[] aabbMin = new float[count * 3];
            float[] aabbMax = new float[count * 3];
            boolean anyUsable = false;
            Vector3f corner = new Vector3f();

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

                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                for (int c = 0; c < 8; c++) {
                    corner.set((c & 1) == 0 ? 0.0f : 1.0f, (c & 2) == 0 ? 0.0f : 1.0f, (c & 4) == 0 ? 0.0f : 1.0f);
                    matrix.transformPosition(corner);
                    minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
                    maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
                }
                aabbMin[i * 3] = minX; aabbMin[i * 3 + 1] = minY; aabbMin[i * 3 + 2] = minZ;
                aabbMax[i * 3] = maxX; aabbMax[i * 3 + 1] = maxY; aabbMax[i * 3 + 2] = maxZ;
            }
            return new OccluderSet(cubeArray, matrices, inverses, shrinks, usable, aabbMin, aabbMax,
                    anyUsable && count > 1);
        }

        boolean hasOccluders() { return hasOccluders; }
        int count() { return matrices.length; }
        BakedCube cube(int index) { return cubes[index]; }
        Matrix4f matrix(int index) { return matrices[index]; }
        Matrix4f inverse(int index) { return inverses[index]; }
        Vector3f shrink(int index) { return shrinks[index]; }
        boolean usable(int index) { return usable[index]; }

        /** True when {@code point} is outside occluder {@code index}'s model-space AABB. */
        boolean aabbRejects(int index, Vector3f point) {
            int base = index * 3;
            return point.x <= aabbMin[base] || point.x >= aabbMax[base]
                    || point.y <= aabbMin[base + 1] || point.y >= aabbMax[base + 1]
                    || point.z <= aabbMin[base + 2] || point.z >= aabbMax[base + 2];
        }
    }

    private record SamplingContext(TexelSampler sampler, int gridWidth, int gridHeight, int samples) {}

    private static int clampAxis(int value, int maxEdge) {
        return Math.max(1, Math.min(maxEdge, value));
    }
}
