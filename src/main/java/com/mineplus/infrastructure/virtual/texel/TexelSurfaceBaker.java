package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.FaceUvAnalyzer;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.util.DebugLogger;
import java.awt.image.BufferedImage;
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
 * <p>In-plane UV rotation rotates the <i>sampling</i> lookup, not the grid: for a
 * rotation θ (quantized to the nearest 90°), the sample is taken at {@code (u, v)}
 * rotated by −θ about the UV window center. Transparency is cutout-style: only
 * pixels with alpha ≥ 128 contribute to a texel's average, and a fully transparent
 * texel emits no plate so the base display shows through.
 *
 * <p>Budget guards (per-face and per-instance plate ceilings) are applied post-merge
 * in face emission order; over-budget faces fall back to the legacy rendering for
 * that face. Baking failures never break model load — a face without a resolvable
 * PNG simply keeps its existing {@code FaceUvAnalyzer} strategy.
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

                BufferedImage image = imageStore.texture(face.textureName(), modelFile);
                FaceUvAnalyzer.UvPlan plan = FaceUvAnalyzer.analyze(face, mode, image != null);
                if (plan.strategy() != FaceUvAnalyzer.UvPlan.Strategy.TEXEL) {
                    continue;
                }

                TexelSurfacePlan baked = bakeFace(
                        face, faceKey, cube, model, image, detail, settings, occluders, cubeIndex);
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
     * occlusion-probes every texel, samples and quantizes the visible ones, merges
     * the result.
     */
    private static TexelSurfacePlan bakeFace(
            BakedFace face,
            CubeFace faceKey,
            BakedCube cube,
            VirtualModel model,
            BufferedImage image,
            ModelMeta.TexelDetail detail,
            TexelBakingSettings settings,
            OccluderSet occluders,
            int cubeIndex
    ) {
        float[] pixelSize = FaceUvAnalyzer.facePixelSize(faceKey, cube);
        int gridWidth = clampAxis(Math.round(pixelSize[0]), settings.maxGridEdge());
        int gridHeight = clampAxis(Math.round(pixelSize[1]), settings.maxGridEdge());

        SamplingContext context = new SamplingContext(
                face,
                gridWidth,
                gridHeight,
                image,
                image.getWidth() / (float) model.resolution().width(),
                image.getHeight() / (float) model.resolution().height(),
                detail.sampleCount()
        );
        Matrix4f ownMatrix = occluders.matrix(cubeIndex);

        int[] grid = new int[gridWidth * gridHeight];
        int occludedCells = 0;
        for (int row = 0; row < gridHeight; row++) {
            int previous = -1;
            for (int column = 0; column < gridWidth; column++) {
                if (isOccluded(occluders, ownMatrix, cubeIndex, faceKey, cube,
                        column, row, gridWidth, gridHeight)) {
                    grid[row * gridWidth + column] = -1;
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
                gridWidth, gridHeight, rects, dominantIndex, dominantArea, occludedCells);
    }

    /**
     * Whether the plate rendered for texel {@code (column, row)} would sit inside
     * another cube's solid. The probe is the exact unit-space point the emitter
     * renders the plate at (tangent fractions, plate surface offset outward),
     * transformed through the cube's own matrix into model space, then tested
     * against each other cube's oriented box via its inverse matrix.
     */
    private static boolean isOccluded(
            OccluderSet occluders,
            Matrix4f ownMatrix,
            int ownIndex,
            CubeFace faceKey,
            BakedCube cube,
            int column,
            int row,
            int gridWidth,
            int gridHeight
    ) {
        if (!occluders.hasOccluders()) {
            return false;
        }
        int normalAxis = faceKey.normalAxis();
        float normalScale = Math.max(Math.abs(cube.scale().get(normalAxis)), 1.0e-6f);
        float surfaceLocal = PLATE_SURFACE_OFFSET_BLOCKS / normalScale;

        Vector3f unit = new Vector3f();
        unit.setComponent(faceKey.uAxis(), (column + 0.5f) / gridWidth);
        unit.setComponent(faceKey.vAxis(), 1.0f - (row + 0.5f) / gridHeight);
        unit.setComponent(normalAxis, faceKey.positiveNormal() ? 1.0f + surfaceLocal : -surfaceLocal);
        Vector3f world = ownMatrix.transformPosition(unit);

        Vector3f local = new Vector3f();
        for (int i = 0; i < occluders.count(); i++) {
            if (i == ownIndex || !occluders.usable(i)) {
                continue;
            }
            occluders.inverse(i).transformPosition(world, local);
            Vector3f shrink = occluders.shrink(i);
            if (local.x > shrink.x && local.x < 1.0f - shrink.x
                    && local.y > shrink.y && local.y < 1.0f - shrink.y
                    && local.z > shrink.z && local.z < 1.0f - shrink.z) {
                return true;
            }
        }
        return false;
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
     * Samples one texel's texture footprint and quantizes the average color.
     *
     * @param previousIndex the previous texel's palette entry in this row (run
     *                      continuity for match hysteresis), or {@code -1}
     * @return palette index, or {@code -1} when the texel is fully transparent
     */
    private static int sampleTexel(SamplingContext context, int column, int row, int previousIndex) {
        BakedFace face = context.face();
        long redSum = 0;
        long greenSum = 0;
        long blueSum = 0;
        int samples = 0;

        for (int sy = 0; sy < context.samples(); sy++) {
            for (int sx = 0; sx < context.samples(); sx++) {
                float fu = (column + (sx + 0.5f) / context.samples()) / context.gridWidth();
                float fv = (row + (sy + 0.5f) / context.samples()) / context.gridHeight();

                // Display position -> window UV (resolution-space texture pixels).
                float u = face.u1() + fu * (face.u2() - face.u1());
                float v = face.v1() + fv * (face.v2() - face.v1());

                // In-plane UV rotation: sample at (u, v) rotated by -theta about the
                // window center. 90-degree steps are exact integer remaps.
                float du = u - (face.u1() + face.u2()) * 0.5f;
                float dv = v - (face.v1() + face.v2()) * 0.5f;
                float su;
                float sv;
                switch (context.rotationSteps()) {
                    case 1 -> {
                        su = dv;
                        sv = -du;
                    }
                    case 2 -> {
                        su = -du;
                        sv = -dv;
                    }
                    case 3 -> {
                        su = -dv;
                        sv = du;
                    }
                    default -> {
                        su = du;
                        sv = dv;
                    }
                }
                u = (face.u1() + face.u2()) * 0.5f + su;
                v = (face.v1() + face.v2()) * 0.5f + sv;

                // Clamp into the window, then map resolution-space UV -> PNG pixels.
                u = clamp(u, context.uMin(), context.uMax());
                v = clamp(v, context.vMin(), context.vMax());
                int px = clampInt((int) Math.floor(u * context.pngScaleX()),
                        0, context.image().getWidth() - 1);
                int py = clampInt((int) Math.floor(v * context.pngScaleY()),
                        0, context.image().getHeight() - 1);

                int argb = context.image().getRGB(px, py);
                if (((argb >>> 24) & 0xFF) >= 128) {
                    redSum += (argb >>> 16) & 0xFF;
                    greenSum += (argb >>> 8) & 0xFF;
                    blueSum += argb & 0xFF;
                    samples++;
                }
            }
        }

        if (samples == 0) {
            return -1;
        }
        return TexelPalette.match(
                (int) (redSum / samples),
                (int) (greenSum / samples),
                (int) (blueSum / samples),
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

        private final Matrix4f[] matrices;
        private final Matrix4f[] inverses;
        private final Vector3f[] shrinks;
        private final boolean[] usable;
        private final boolean hasOccluders;

        private OccluderSet(Matrix4f[] matrices, Matrix4f[] inverses,
                            Vector3f[] shrinks, boolean[] usable, boolean hasOccluders) {
            this.matrices = matrices;
            this.inverses = inverses;
            this.shrinks = shrinks;
            this.usable = usable;
            this.hasOccluders = hasOccluders;
        }

        static OccluderSet build(List<BakedCube> cubes) {
            int count = cubes == null ? 0 : cubes.size();
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
            return new OccluderSet(matrices, inverses, shrinks, usable,
                    anyUsable && count > 1);
        }

        boolean hasOccluders() {
            return hasOccluders;
        }

        int count() {
            return matrices.length;
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

    /** Precomputed per-face sampling parameters. */
    private record SamplingContext(
            BakedFace face,
            int gridWidth,
            int gridHeight,
            BufferedImage image,
            float pngScaleX,
            float pngScaleY,
            int samples
    ) {

        float uMin() {
            return Math.min(face().u1(), face().u2());
        }

        float uMax() {
            return Math.max(face().u1(), face().u2());
        }

        float vMin() {
            return Math.min(face().v1(), face().v2());
        }

        float vMax() {
            return Math.max(face().v1(), face().v2());
        }

        int rotationSteps() {
            return Math.round(face().rotation() / 90.0f) % 4;
        }
    }

    private static int clampAxis(int value, int maxEdge) {
        return Math.max(1, Math.min(maxEdge, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
