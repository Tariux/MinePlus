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
 * with geometry, not with texture resolution.
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
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans = new ArrayList<>(model.cubes().size());
        int facesBaked = 0;
        int facesTotal = 0;
        int totalPlates = 0;
        int maxPlatesOnFace = 0;
        int faceBudgetFallbacks = 0;
        int instanceBudgetFallbacks = 0;
        int runningPlates = 0;
        Map<String, Integer> gridHistogram = new LinkedHashMap<>();
        Map<Integer, Integer> paletteUsage = new LinkedHashMap<>();

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

                TexelSurfacePlan baked = bakeFace(face, faceKey, cube, model, image, detail, settings);
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
                maxPlatesPerInstance
        );
    }

    /**
     * Bakes one face: derives the effective grid from the face's physical size,
     * samples and quantizes every texel, merges the result.
     */
    private static TexelSurfacePlan bakeFace(
            BakedFace face,
            CubeFace faceKey,
            BakedCube cube,
            VirtualModel model,
            BufferedImage image,
            ModelMeta.TexelDetail detail,
            TexelBakingSettings settings
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

        int[] grid = new int[gridWidth * gridHeight];
        for (int row = 0; row < gridHeight; row++) {
            for (int column = 0; column < gridWidth; column++) {
                grid[row * gridWidth + column] = sampleTexel(context, column, row);
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
        return new TexelSurfacePlan(gridWidth, gridHeight, rects, dominantIndex, dominantArea);
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
     * @return palette index, or {@code -1} when the texel is fully transparent
     */
    private static int sampleTexel(SamplingContext context, int column, int row) {
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
                (int) (blueSum / samples)
        );
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
