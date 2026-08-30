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

/**
 * Load-time transformer that decomposes each face's UV-mapped texture into per-pixel
 * texels, quantizes each texel's color to the closest visually-flat vanilla block
 * ({@link TexelPalette}), and merges adjacent same-color texels into maximal
 * rectangles ({@link TexelMerge}).
 *
 * <p>The effective grid is the <i>face's</i> pixel grid (the render ceiling), never
 * the texture's resolution: {@code gridW = round(faceWidthPx)}, with texel
 * {@code (i, j)} sampling the texture at {@code u(i) = u1 + (i + 0.5) · winW / gridW}.
 * A low-resolution texture therefore upscales exactly (constant texels merge into
 * one plate) and a high-resolution texture downsamples — entity count always scales
 * with geometry, not with texture resolution. Colors are the source texture's own,
 * sampled 1:1 per texel; only the block choice is a nearest-flat-color match.
 *
 * <p><b>Occlusion culling (entity-overlap resolution).</b> Box-modeled geometry
 * routinely nests cubes (a label band wrapping a bottle body, a cork inside a neck).
 * Without culling, faces of the wrapped cube that lie <i>inside</i> another cube's
 * solid still emit plates — plates that cross other plates and solids at the seams
 * and read as layered artifacts. The baker therefore probes every texel's plate
 * position (the exact unit-space point {@code DisplayEmitter} renders it at, plate
 * surface offset included) against every other cube's oriented box: a texel buried
 * inside another solid emits no plate. Visible surfaces are untouched — the probe
 * sits where the plate actually renders, so a face is culled only when its plate is
 * genuinely inside another cube's volume.
 *
 * <p><b>Partial-visibility rescue.</b> A texel whose plate <i>center</i> is buried
 * but whose footprint straddles an occluder boundary (an overhanging label band
 * whose outer sliver is exposed) would be lost to the binary center probe. When the
 * center probe is occluded, the four footprint corners are probed as well; a texel
 * with any visible corner emits its plate. The buried fraction of such a plate is
 * hidden inside the neighbor's solid — rendering a visible sliver beats punching a
 * hole — so boundary texels are never silently dropped.
 *
 * <p><b>Enclosed-transparency infill.</b> Sprite atlases routinely pack faces into
 * sub-regions whose windows still contain a few fully transparent <i>padding</i>
 * pixels; a cutout hole punched there exposes the base display's dominant color and
 * reads as a glitch at corners and edges. Transparency connected to the grid
 * boundary (flood-filled from the window's rim) is genuine silhouette cutout and is
 * preserved; <i>enclosed</i> transparent texels are infilled with the majority
 * palette entry of their opaque 4-neighbors (deterministic: highest count, ties to
 * the lowest index; no opaque neighbor leaves the hole). Isolated interior holes —
 * atlas padding, anti-aliased speckle — disappear while real cutouts survive.
 *
 * <p>In-plane UV rotation rotates the <i>sampling</i> lookup, not the grid: for a
 * rotation θ (quantized to the nearest 90°), the sample is taken at {@code (u, v)}
 * rotated by −θ about the UV window center. Transparency is cutout-style with
 * alpha-weighted accumulation: samples with alpha ≥ 128 contribute their color
 * weighted by {@code alpha/255}, and a texel whose covered sample area falls below
 * half emits no plate — anti-aliased sprite rims no longer drag dark blended
 * colors through the cutout threshold as hard halos.
 *
 * <p>Budget guards (per-face and per-instance plate ceilings) are applied post-merge
 * in face emission order; over-budget faces fall back to the legacy rendering for
 * that face. Baking failures never break model load — a face without a resolvable
 * PNG simply keeps its existing {@link FaceUvAnalyzer} strategy.
 */
public final class TexelSurfaceBaker {

    private TexelSurfaceBaker() {
    }

    /**
     * Where a plate's outer surface sits beyond its face, in blocks — must mirror
     * {@code DisplayEmitter.TEXEL_EPS_OUT + PLATE_THICKNESS} so occlusion probes
     * test exactly the geometry that renders.
     */
    private static final float PLATE_SURFACE_OFFSET_BLOCKS = 1.0f / 256.0f + 1.0f / 64.0f;

    /**
     * Tangential containment shrink per axis, in blocks: a probe must be this far
     * inside another cube's border to count as occluded, so faces merely touching a
     * neighbor's side are never culled. Kept below the plate surface offset so
     * exactly-abutting solids do cull their buried faces.
     */
    private static final float OCCLUSION_SHRINK_BLOCKS = 0.01f / 16.0f;

    /** Fraction of the texel footprint a corner probe sits in from the cell edge. */
    private static final float CORNER_PROBE_INSET = 0.05f;

    /** Grid sentinel: texel is fully transparent (candidate for infill). */
    private static final int TRANSPARENT = -1;

    /** Grid sentinel: texel's plate position is buried inside another cube. */
    private static final int OCCLUDED = -2;

    /**
     * Bakes texel plans for every eligible face of a model.
     *
     * @param model      the imported model
     * @param meta       per-model overrides ({@code texelMode}/{@code texelDetail})
     * @param modelFile  the source {@code .bbmodel} file (next-to-model texture
     *                   lookup); may be {@code null} for API-registered models
     * @param imageStore decoded-PNG cache shared across models
     * @param settings   global texel baking settings (budgets, mode, detail)
     */
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

                TextureImageStore.TextureRaster raster =
                        imageStore.raster(face.textureName(), modelFile);
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
        if (faceBudgetFallbacks > 0 || instanceBudgetFallbacks > 0) {
            DebugLogger.warning("[TexelBaking] Model '" + model.name() + "': "
                    + faceBudgetFallbacks + " face(s) exceeded the per-face plate budget ("
                    + maxPlatesPerFace + ") and " + instanceBudgetFallbacks
                    + " face(s) fell back to stay within the per-instance budget ("
                    + maxPlatesPerInstance + "). "
                    + "Treat 'model renders at budget' as a content-authoring bug: "
                    + "use flatter textures, raise the budgets, or set maxTexelPlatesPerFace/"
                    + "maxTexelPlatesPerInstance in the model's .meta.json.");
        }

        return new TexelBakeResult(
                true,
                mode,
                detail,
                cubePlans,
                facesBaked,
                facesTotal,
                totalPlates,
                maxPlatesOnFace,
                faceBudgetFallbacks,
                instanceBudgetFallbacks,
                elapsedNanos,
                gridHistogram,
                paletteUsage,
                maxPlatesPerFace,
                maxPlatesPerInstance,
                occludedCells
        );
    }

    /**
     * Bakes one face: derives the effective grid from the face's physical size,
     * occlusion-probes every texel (center probe, corner rescue), samples and
     * quantizes the visible ones with alpha-weighted accumulation, infills
     * enclosed transparency, merges the result.
     */
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
        // Scratch vectors reused across every probe of this face: the bake loop is
        // allocation-free below the per-face sampler and grid arrays.
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
                int index = sampleTexel(context, column, row, previous);
                grid[row * gridWidth + column] = index;
                if (index >= 0) {
                    previous = index;
                }
            }
        }
        infillEnclosedTransparency(grid, gridWidth, gridHeight, occluders, ownMatrix,
                cubeIndex, faceKey, cube, unit, world, local);
        int cutoutCells = 0;
        for (int value : grid) {
            if (value == TRANSPARENT) {
                cutoutCells++;
            }
        }
        List<TexelSurfacePlan.Rect> rects = TexelMerge.merge(grid, gridWidth, gridHeight);
        int[] areaByIndex = paletteAreas(rects);
        int dominantIndex = -1;
        int dominantArea = 0;
        for (int i = 0; i < areaByIndex.length; i++) {
            if (areaByIndex[i] > dominantArea) {
                dominantArea = areaByIndex[i];
                dominantIndex = i;
            }
        }
        return new TexelSurfacePlan(
                gridWidth, gridHeight, rects, dominantIndex, dominantArea, occludedCells, cutoutCells);
    }

    /**
     * Whether the plate rendered for texel {@code (column, row)} would sit inside
     * another cube's solid: the center probe decides, and only a fully buried
     * center falls through to the four corner probes (partial-visibility rescue).
     * All probes use the exact unit-space points the emitter renders the plate at
     * (tangent fractions, plate surface offset outward), transformed through the
     * cube's own matrix into model space, then tested against each other cube's
     * oriented box via its inverse matrix.
     */
    private static boolean cellOccluded(
            OccluderSet occluders,
            Matrix4f ownMatrix,
            int ownIndex,
            CubeFace faceKey,
            BakedCube cube,
            int column,
            int row,
            int gridWidth,
            int gridHeight,
            Vector3f unit,
            Vector3f world,
            Vector3f local,
            Map<BakedFace, TexelSampler> samplers,
            VirtualModel model,
            File modelFile,
            TextureImageStore imageStore
    ) {
        if (!occluders.hasOccluders()) {
            return false;
        }
        if (!pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube,
                (column + 0.5f) / gridWidth, 1.0f - (row + 0.5f) / gridHeight,
                unit, world, local, samplers, model, modelFile, imageStore)) {
            return false;
        }
        float u0 = (column + CORNER_PROBE_INSET) / gridWidth;
        float u1 = (column + 1.0f - CORNER_PROBE_INSET) / gridWidth;
        float v0 = 1.0f - (row + CORNER_PROBE_INSET) / gridHeight;
        float v1 = 1.0f - (row + 1.0f - CORNER_PROBE_INSET) / gridHeight;
        return pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u0, v0,
                        unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u1, v0,
                        unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u0, v1,
                        unit, world, local, samplers, model, modelFile, imageStore)
                && pointOccluded(occluders, ownMatrix, ownIndex, faceKey, cube, u1, v1,
                        unit, world, local, samplers, model, modelFile, imageStore);
    }

    /**
     * Single containment probe at face fractions {@code (cu, cv)} into scratch
     * vectors. A probe inside an occluder's volume is <b>transparency-aware</b>:
     * the sightline from the plate runs along the face's outward normal, so the
     * occluder's covering surface is its face with the <i>same</i> normal key; if
     * that face's texel at the projected exit point is transparent (cutout), the
     * occluder does not hide the plate — layered cutout art (a transparent glass
     * band with the label showing through) renders its inner layers exactly like
     * vanilla's painter model.
     */
    private static boolean pointOccluded(
            OccluderSet occluders,
            Matrix4f ownMatrix,
            int ownIndex,
            CubeFace faceKey,
            BakedCube cube,
            float cu,
            float cv,
            Vector3f unit,
            Vector3f world,
            Vector3f local,
            Map<BakedFace, TexelSampler> samplers,
            VirtualModel model,
            File modelFile,
            TextureImageStore imageStore
    ) {
        int normalAxis = faceKey.normalAxis();
        float normalScale = Math.max(Math.abs(cube.scale().get(normalAxis)), 1.0e-6f);
        float surfaceLocal = PLATE_SURFACE_OFFSET_BLOCKS / normalScale;

        unit.setComponent(faceKey.uAxis(), cu);
        unit.setComponent(faceKey.vAxis(), cv);
        unit.setComponent(normalAxis, faceKey.positiveNormal() ? 1.0f + surfaceLocal : -surfaceLocal);
        ownMatrix.transformPosition(unit, world);

        for (int i = 0; i < occluders.count(); i++) {
            if (i == ownIndex || !occluders.usable(i)) {
                continue;
            }
            occluders.inverse(i).transformPosition(world, local);
            Vector3f shrink = occluders.shrink(i);
            if (local.x > shrink.x && local.x < 1.0f - shrink.x
                    && local.y > shrink.y && local.y < 1.0f - shrink.y
                    && local.z > shrink.z && local.z < 1.0f - shrink.z) {
                if (seeThrough(occluders.cube(i), faceKey, local,
                        samplers, model, modelFile, imageStore)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an occluder's covering texel is transparent. The covering surface is
     * the occluder's face with the probe's normal key (the sightline exits through
     * it); the exit point keeps the probe's local tangential coordinates, converted
     * to face fractions with the same {@code (fu, 1 - fv)} convention the plates
     * position themselves with.
     */
    private static boolean seeThrough(
            BakedCube occluder,
            CubeFace faceKey,
            Vector3f local,
            Map<BakedFace, TexelSampler> samplers,
            VirtualModel model,
            File modelFile,
            TextureImageStore imageStore
    ) {
        BakedFace face = occluder.faces().get(faceKey);
        if (face == null || face.textureName() == null || face.textureName().isBlank()) {
            return false;
        }
        TexelSampler sampler = samplers.get(face);
        if (sampler == null && !samplers.containsKey(face)) {
            TextureImageStore.TextureRaster raster = imageStore.raster(face.textureName(), modelFile);
            sampler = raster != null ? new TexelSampler(face, raster, model.resolution()) : null;
            samplers.put(face, sampler);
        }
        if (sampler == null) {
            return false;
        }
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
     * Enclosed-transparency infill, <b>backing-aware</b>. Transparent texels
     * reachable from the grid rim are genuine silhouette cutout and always stay.
     * Enclosed transparent texels split by what lies behind them along the inward
     * face normal: with another cube's volume on that ray (layered cutout art — a
     * label behind a transparent band) they are genuine see-through holes and
     * stay; with nothing behind (atlas padding on an outermost face) they are
     * infilled with the majority palette entry of their opaque 4-neighbors.
     * Mutates {@code grid} in place.
     */
    private static void infillEnclosedTransparency(
            int[] grid,
            int width,
            int height,
            OccluderSet occluders,
            Matrix4f ownMatrix,
            int ownIndex,
            CubeFace faceKey,
            BakedCube cube,
            Vector3f unit,
            Vector3f world,
            Vector3f local
    ) {
        int cells = width * height;
        int transparentCount = 0;
        for (int value : grid) {
            if (value == TRANSPARENT) {
                transparentCount++;
            }
        }
        if (transparentCount == 0) {
            return;
        }

        // Flood fill from rim transparency: marks genuine cutout cells. The queue
        // is bounded by the transparent count because every cell seeds at most once.
        boolean[] genuine = new boolean[cells];
        int[] queue = new int[transparentCount];
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = seedRim(grid, genuine, queue, tail, x);
            tail = seedRim(grid, genuine, queue, tail, (height - 1) * width + x);
        }
        for (int y = 0; y < height; y++) {
            tail = seedRim(grid, genuine, queue, tail, y * width);
            tail = seedRim(grid, genuine, queue, tail, y * width + width - 1);
        }
        int head = 0;
        while (head < tail) {
            int cell = queue[head++];
            int x = cell % width;
            int y = cell / width;
            if (x > 0) {
                tail = seedRim(grid, genuine, queue, tail, cell - 1);
            }
            if (x < width - 1) {
                tail = seedRim(grid, genuine, queue, tail, cell + 1);
            }
            if (y > 0) {
                tail = seedRim(grid, genuine, queue, tail, cell - width);
            }
            if (y < height - 1) {
                tail = seedRim(grid, genuine, queue, tail, cell + width);
            }
        }

        // Enclosed transparent texels: keep when layered content sits behind
        // them, otherwise infill with the majority neighbor entry.
        int[] neighborCounts = new int[TexelPalette.size()];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int cell = y * width + x;
                if (grid[cell] != TRANSPARENT || genuine[cell]) {
                    continue;
                }
                if (backed(occluders, ownMatrix, ownIndex, faceKey, cube,
                        (x + 0.5f) / width, 1.0f - (y + 0.5f) / height, unit, world, local)) {
                    continue;
                }
                int majority = majorityNeighbor(grid, width, height, x, y, neighborCounts);
                if (majority >= 0) {
                    grid[cell] = majority;
                }
            }
        }
    }

    /**
     * Whether another cube's volume lies behind a face cell along the inward
     * normal: an affine ray (origin at the cell's plate position, direction
     * inward) is slab-tested against every other usable cube's local unit box.
     * Backed enclosed holes are layered see-through art, not atlas padding.
     */
    private static boolean backed(
            OccluderSet occluders,
            Matrix4f ownMatrix,
            int ownIndex,
            CubeFace faceKey,
            BakedCube cube,
            float cu,
            float cv,
            Vector3f unit,
            Vector3f world,
            Vector3f local
    ) {
        if (!occluders.hasOccluders()) {
            return false;
        }
        int normalAxis = faceKey.normalAxis();
        float normalScale = Math.max(Math.abs(cube.scale().get(normalAxis)), 1.0e-6f);
        float surfaceLocal = PLATE_SURFACE_OFFSET_BLOCKS / normalScale;

        unit.setComponent(faceKey.uAxis(), cu);
        unit.setComponent(faceKey.vAxis(), cv);
        unit.setComponent(normalAxis, faceKey.positiveNormal() ? 1.0f + surfaceLocal : -surfaceLocal);
        ownMatrix.transformPosition(unit, world);

        // Inward direction in model space: the face normal flipped, through the
        // cube's own transform (affine direction; magnitude is irrelevant).
        Vector3f inward = new Vector3f();
        inward.setComponent(normalAxis, faceKey.positiveNormal() ? -1.0f : 1.0f);
        ownMatrix.transformDirection(inward);

        for (int i = 0; i < occluders.count(); i++) {
            if (i == ownIndex || !occluders.usable(i)) {
                continue;
            }
            occluders.inverse(i).transformPosition(world, local);
            Vector3f dir = new Vector3f(inward);
            occluders.inverse(i).transformDirection(dir);
            if (rayHitsUnitBox(local, dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Slab test of an affine ray against the local unit box {@code [0,1]^3}:
     * any intersection with {@code tExit > 0} counts (content along the ray,
     * including behind the origin for robustness against the plate offset).
     */
    private static boolean rayHitsUnitBox(Vector3f origin, Vector3f direction) {
        float tEnter = -Float.MAX_VALUE;
        float tExit = Float.MAX_VALUE;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {direction.x, direction.y, direction.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-12f) {
                if (o[axis] < 0.0f || o[axis] > 1.0f) {
                    return false;
                }
                continue;
            }
            float t1 = (0.0f - o[axis]) / d[axis];
            float t2 = (1.0f - o[axis]) / d[axis];
            if (t1 > t2) {
                float t = t1;
                t1 = t2;
                t2 = t;
            }
            tEnter = Math.max(tEnter, t1);
            tExit = Math.min(tExit, t2);
            if (tEnter > tExit) {
                return false;
            }
        }
        return tExit > 0.0f;
    }

    /** Seeds a transparent, unmarked cell into the flood queue; returns the new tail. */
    private static int seedRim(int[] grid, boolean[] genuine, int[] queue, int tail, int cell) {
        if (grid[cell] == TRANSPARENT && !genuine[cell]) {
            genuine[cell] = true;
            queue[tail++] = cell;
        }
        return tail;
    }

    /**
     * Majority palette entry among the 4-neighbors of a cell: highest count wins,
     * ties resolve to the lowest palette index (deterministic). {@code -1} when no
     * neighbor carries a palette entry (all transparent or occluded).
     */
    private static int majorityNeighbor(
            int[] grid, int width, int height, int x, int y, int[] neighborCounts) {
        java.util.Arrays.fill(neighborCounts, 0);
        if (x > 0 && grid[y * width + x - 1] >= 0) {
            neighborCounts[grid[y * width + x - 1]]++;
        }
        if (x < width - 1 && grid[y * width + x + 1] >= 0) {
            neighborCounts[grid[y * width + x + 1]]++;
        }
        if (y > 0 && grid[(y - 1) * width + x] >= 0) {
            neighborCounts[grid[(y - 1) * width + x]]++;
        }
        if (y < height - 1 && grid[(y + 1) * width + x] >= 0) {
            neighborCounts[grid[(y + 1) * width + x]]++;
        }
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < neighborCounts.length; i++) {
            if (neighborCounts[i] > bestCount) {
                bestCount = neighborCounts[i];
                best = i;
            }
        }
        return best;
    }

    /** Total cell area per palette entry across a merged rectangle list. */
    private static int[] paletteAreas(List<TexelSurfacePlan.Rect> rects) {
        int[] areaByIndex = new int[TexelPalette.size()];
        for (TexelSurfacePlan.Rect rect : rects) {
            areaByIndex[rect.paletteIndex()] += rect.width() * rect.height();
        }
        return areaByIndex;
    }

    /**
     * Samples one texel's texture footprint and quantizes the alpha-weighted average
     * color. Samples with alpha ≥ 128 contribute weighted by {@code alpha/255}; a
     * texel whose covered area is below half the footprint emits no plate (cutout),
     * so anti-aliased rims neither leak blended dark colors nor survive as speckle.
     *
     * @param previousIndex the previous texel's palette entry in this row (run
     *                      continuity for match hysteresis), or {@code -1}
     * @return palette index, or {@code -1} when the texel emits no plate
     */
    private static int sampleTexel(SamplingContext context, int column, int row, int previousIndex) {
        double redSum = 0;
        double greenSum = 0;
        double blueSum = 0;
        double weightSum = 0;
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
                previousIndex,
                MATCH_TIE_TOLERANCE
        );
    }

    /**
     * Near-tie tolerance for palette matching hysteresis: a run's current entry wins
     * while its distance stays within this factor of the best entry's distance.
     * Collapses shade-gradient noise into coherent runs without affecting genuinely
     * different colors.
     */
    private static final float MATCH_TIE_TOLERANCE = 1.20f;

    /**
     * Per-model occluder geometry: each cube's display matrix (matching
     * {@code DisplayEmitter.cubeMatrix}), its inverse for containment probes, and a
     * per-axis unit-space shrink derived from the cube's extent. Cubes with a
     * degenerate axis cannot occlude (zero volume along that axis) and are marked
     * unusable; the inverses of singular matrices are never consulted.
     */
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
                boolean degenerate = Math.abs(scale.x) < 1.0e-6f
                        || Math.abs(scale.y) < 1.0e-6f
                        || Math.abs(scale.z) < 1.0e-6f;
                usable[i] = !degenerate;
                anyUsable |= !degenerate;
                inverses[i] = degenerate ? null : new Matrix4f(matrix).invert();
                shrinks[i] = new Vector3f(
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.x), 1.0e-6f),
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.y), 1.0e-6f),
                        OCCLUSION_SHRINK_BLOCKS / Math.max(Math.abs(scale.z), 1.0e-6f));
            }
            return new OccluderSet(cubeArray, matrices, inverses, shrinks, usable,
                    anyUsable && count > 1);
        }

        boolean hasOccluders() {
            return hasOccluders;
        }

        int count() {
            return matrices.length;
        }

        BakedCube cube(int index) {
            return cubes[index];
        }

        Matrix4f matrix(int index) {
            return matrices[index];
        }

        Matrix4f inverse(int index) {
            return inverses[index];
        }

        Vector3f shrink(int index) {
            return shrinks[index];
        }

        boolean usable(int index) {
            return usable[index];
        }
    }

    /** Precomputed per-face sampling parameters over the shared {@link TexelSampler}. */
    private record SamplingContext(
            TexelSampler sampler,
            int gridWidth,
            int gridHeight,
            int samples
    ) {
    }

    private static int clampAxis(int value, int maxEdge) {
        return Math.max(1, Math.min(maxEdge, value));
    }
}
