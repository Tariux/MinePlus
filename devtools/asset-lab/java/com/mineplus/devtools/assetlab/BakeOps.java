package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.BbModelImporter;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.DisplayEmitter;
import com.mineplus.infrastructure.virtual.FaceUvAnalyzer;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelPalette;
import com.mineplus.infrastructure.virtual.texel.TexelSampler;
import com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker;
import com.mineplus.infrastructure.virtual.texel.TexelSurfacePlan;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.util.DebugLogger;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.Material;

/**
 * The daemon's {@code bake} op: run the real texel surface baker and, on top of
 * it, the real {@link DisplayEmitter} — so the browser draws exactly the display
 * list the server would spawn, not a re-derivation of the geometry in JS.
 *
 * <p>Ported from the texel tool's {@code BakeDaemon.bake()} (same fields, same
 * settings merge semantics) with the emitted display list appended: parity with
 * {@code VirtualBlockManager}'s spawn path is structural. The emitter calls mirror
 * {@code VirtualBlockManager} 542-548 (per-cube {@code emitCube(cube,
 * settings.perFaceRendering(), facePlans, model.resolution(), fallbackTints)}) and
 * the brightness rule mirrors line 540 (the {@code texelBrightness} floor only
 * applies while texel plans exist) and 605-612 / 631-638
 * ({@code max(emission, floor) > 0} is the only case the engine calls
 * {@code setBrightness(BRIGHTNESS_BY_LEVEL[min(emission, 15)])}, always
 * {@code (level, sky 15)}).</p>
 *
 * <p>Request:
 * <pre>{@code
 * {"op":"bake","model":".../model.bbmodel","textureRoot":"...",   // textureRoot optional
 *  "detail":"FACE","mode":"AUTO","maxPlatesPerFace":96,"maxPlatesPerInstance":150,
 *  "perFaceRendering":true,"texelEnabled":true,"originMode":"AUTO","maxGridEdge":64,
 *  "includeDisplays":true,"wantPack":true,"namespace":"mineplus",
 *  "meta":{"texelMode":"ON", ...}}                                 // explicit .meta.json overrides
 * }</pre>
 * Every flag may also be nested inside an {@code overrides} object (the texel
 * tool's spelling) — flat request keys win when both are present.</p>
 *
 * <p>Response additions over the texel tool's shape: {@code displays} (the real
 * emitted list), {@code solidDisplays} (the same entries that are not texel
 * plates), {@code displayCount} / {@code solidDisplayCount} /
 * {@code texelDisplayCount}, {@code shading}, {@code brightness},
 * {@code brightnessFloor} and — when {@code wantPack} is set — {@code packModel}
 * plus {@code packItemModel}.</p>
 */
final class BakeOps {

    private BakeOps() {
    }

    /**
     * One emitted display, the cube it came from, the face it skins ({@code null}
     * for the whole-block base display) and the rendering tier it is on.
     */
    private record Labelled(
            DisplayEmitter.EmittedDisplay display, BakedCube cube, CubeFace face, String mode) {
    }

    /** Neutral gray for a display whose colour genuinely cannot be recovered. */
    private static final int FALLBACK_RGB = 0x9A9A9A;

    /** The five emission tiers {@code DisplayEmitter} can produce, as {@code mode}. */
    private static final String MODE_TEXEL = "texel";
    private static final String MODE_SOLID = "solid";
    private static final String MODE_PLATE = "plate";
    private static final String MODE_TILE = "tile";
    private static final String MODE_CROP = "crop";

    /** The {@code face} value of a whole-block base display. */
    private static final String FACE_WHOLE = "whole";

    // ------------------------------------------------------------------
    // bake op
    // ------------------------------------------------------------------

    static JsonObject bake(JsonObject raw) throws Exception {
        JsonObject request = mergedRequest(raw);
        String modelPath = MobOps.Json.str(request, "model", null);
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException("the bake op needs 'model'");
        }
        File modelFile = new File(modelPath);
        if (!modelFile.isFile()) {
            throw new IllegalArgumentException("model file not found: " + modelPath);
        }

        String key = MobOps.stripExtension(modelFile.getName());
        VirtualModel model = BbModelImporter.parse(key, modelFile, Logger.getLogger("mineplus-devtool"));
        if (model == null) {
            throw new IllegalStateException("BbModelImporter rejected '" + modelPath + "'");
        }

        ModelMeta fileMeta = ModelMeta.load(modelFile);
        ModelMeta meta = mergeMeta(fileMeta, object(request, "meta"));

        TexelBakingSettings settings = new TexelBakingSettings(
                bool(request, "texelEnabled", true),
                ModelMeta.TexelMode.fromKey(str(request, "mode", null), ModelMeta.TexelMode.AUTO),
                ModelMeta.TexelDetail.fromKey(str(request, "detail", null), ModelMeta.TexelDetail.FACE),
                intg(request, "maxPlatesPerFace", 96),
                intg(request, "maxPlatesPerInstance", 150),
                intg(request, "maxGridEdge", 64),
                bool(request, "uniformAreaDetection",
                        TexelBakingSettings.defaults().uniformAreaDetection()),
                intg(request, "uniformAreaMinSize",
                        TexelBakingSettings.defaults().uniformAreaMinSize()),
                (float) dbl(request, "uniformAreaOklabThreshold",
                        TexelBakingSettings.defaults().uniformAreaOklabThreshold()),
                bool(request, "adaptiveBudgeting", TexelBakingSettings.defaults().adaptiveBudgeting()),
                bool(request, "budgetFallbackToSimpleColor",
                        TexelBakingSettings.defaults().budgetFallbackToSimpleColor()),
                bool(request, "reuseSymmetricFaces", TexelBakingSettings.defaults().reuseSymmetricFaces()));
        ModelMeta.OriginMode originMode = resolveOriginMode(request, meta, model);
        boolean perFaceRendering = bool(request, "perFaceRendering", true);

        TextureImageStore store = new TextureImageStore(textureRoot(request, modelFile));

        Set<String> resolvedTextures = new HashSet<>();
        JsonArray texturesJson = new JsonArray();
        int unresolved = 0;
        for (String textureName : model.textureNames()) {
            BufferedImage image = store.texture(textureName, modelFile);
            boolean resolved = image != null;
            if (resolved) {
                resolvedTextures.add(textureName);
            } else {
                unresolved++;
            }
            JsonObject tj = new JsonObject();
            tj.addProperty("name", textureName);
            tj.addProperty("resolved", resolved);
            if (resolved) {
                tj.addProperty("width", image.getWidth());
                tj.addProperty("height", image.getHeight());
                File path = resolvedTexturePath(store, textureName, modelFile);
                if (path != null) {
                    tj.addProperty("path", path.getAbsolutePath());
                }
                byte[] png = pngBytes(image);
                if (png != null) {
                    tj.addProperty("pngBase64", Base64.getEncoder().encodeToString(png));
                }
            }
            texturesJson.add(tj);
        }

        TexelBakeResult bake = TexelSurfaceBaker.bakeModel(model, meta, modelFile, store, settings);

        JsonObject response = new JsonObject();
        response.addProperty("key", key);
        response.addProperty("name", model.name());
        response.addProperty("modelFormat", model.modelFormat() == null ? "" : model.modelFormat());
        response.addProperty("originMode", originMode.name());
        response.addProperty("cubeCount", model.cubes().size());
        response.addProperty("animated", model.hasAnimations());
        response.addProperty("hasMetaFile", !fileMeta.isEmpty());
        response.add("meta", metaJson(fileMeta));
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", model.resolution().width());
        resolution.addProperty("height", model.resolution().height());
        response.add("resolution", resolution);
        response.add("textures", texturesJson);
        response.add("palette", paletteJson());
        response.add("cubes", cubesJson(model, settings.effectiveMode(meta), resolvedTextures));
        response.add("texel", texelJson(bake));
        response.addProperty("perFaceRendering", perFaceRendering);

        // The real emitted display list, exactly as VirtualBlockManager would spawn it.
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans =
                bake.enabled() ? bake.cubePlans() : null;
        // VirtualBlockManager:540 — the meta floor only applies while plans exist.
        int brightnessFloor = cubePlans != null && meta.texelBrightness() != null
                ? meta.texelBrightness() : 0;
        Stats stats = appendDisplays(response, model, modelFile, store, cubePlans, bake,
                brightnessFloor, perFaceRendering, bool(request, "includeDisplays", true));
        response.add("shading", virtualBlockShading());
        response.add("brightness", brightnessPolicy(brightnessFloor));
        response.addProperty("brightnessFloor", brightnessFloor);

        if (bool(request, "wantPack", false)) {
            appendPackModel(response, model, originMode, str(request, "namespace", "mineplus"), key);
        }

        StringBuilder summary = new StringBuilder();
        summary.append(model.cubes().size()).append(" cube(s) → ").append(stats.total)
                .append(" display(s) (").append(stats.solid).append(" plate/tile/crop, ")
                .append(stats.texel).append(" texel plate)");
        if (bake.enabled()) {
            summary.append("; texel baked ").append(bake.facesBaked()).append('/')
                    .append(bake.facesTotal()).append(" face(s), ")
                    .append(bake.totalPlates()).append(" plate(s)");
            if (bake.budgetFallbackFaces() > 0) {
                summary.append(" with ").append(bake.budgetFallbackFaces())
                        .append(" budget fallback(s)");
            }
        } else {
            summary.append("; texel baking disabled for this model");
        }
        if (unresolved > 0) {
            summary.append(". ").append(unresolved)
                    .append(" texture(s) are not on disk — those faces would render the"
                            + " missing texture in game");
            response.addProperty("status", "REPAIRED");
        } else {
            response.addProperty("status", "OK");
        }
        response.addProperty("summary", summary.toString());
        return response;
    }

    // ------------------------------------------------------------------
    // The emitted display list (shared with the block op)
    // ------------------------------------------------------------------

    /** Counters produced while emitting, for callers that only need the totals. */
    record Stats(int total, int solid, int texel, int maxEmission, int overrides) {
    }

    /**
     * Emit + serialize the display list the server would spawn for {@code model}.
     *
     * <p>Attribution is never re-derived. With {@code perFaceRendering} on (the
     * server default — {@code VirtualRenderingSettings.perFaceRendering()} is true)
     * the emitter runs once per cube <em>per face</em>, with a single-entry
     * {@code EnumMap} of that face's texel plan, so every display it returns belongs
     * to that face by construction. That is exactly equivalent to the real whole-cube
     * call: {@code DisplayEmitter}'s per-face loop composes each plate as
     * {@code cubeMatrix(cube) x faceLocal}, and the only cross-face inputs — the
     * {@code allFacesPresent} and {@code hasCutout} gates — feed the whole-block fast
     * path, which {@code perFaceRendering} makes unreachable. The one-face cube
     * copies the transforms, primary texture, light emission, bone index and geometry
     * verbatim, so the numbers are identical rather than approximated.</p>
     *
     * <p>With {@code perFaceRendering} off the real whole-cube call is made once and,
     * when its base-display fast path fires, that single display is reported as
     * {@code face:"whole"} / {@code mode:"solid"}; when the fast path is blocked by a
     * missing face the per-face calls still attribute exactly and produce the same
     * geometry.</p>
     *
     * @param cubePlans       texel plans per cube, or {@code null} for the plain
     *                        virtual block path (that is what the block op passes)
     * @param bake            the bake result whose per-cube budget-fallback tints
     *                        the emitter consumes (may be {@code null})
     * @param brightnessFloor the engine's {@code texelBrightness} floor (0 when the
     *                        model renders without texel plans)
     */
    static Stats appendDisplays(
            JsonObject response,
            VirtualModel model,
            File modelFile,
            TextureImageStore store,
            List<Map<CubeFace, TexelSurfacePlan>> cubePlans,
            TexelBakeResult bake,
            int brightnessFloor,
            boolean perFaceRendering,
            boolean includeDisplays
    ) {
        JsonArray displays = includeDisplays ? new JsonArray() : null;
        JsonArray emitErrors = null;
        Map<BakedFace, Integer> sampleCache = new IdentityHashMap<>();
        Map<Material, Integer> paletteByMaterial = paletteByMaterial();
        VirtualModel.Resolution resolution = model.resolution();
        int total = 0;
        int solid = 0;
        int texels = 0;
        int maxEmission = 0;
        int overrides = 0;
        int cubeIndex = 0;
        for (BakedCube cube : model.cubes()) {
            Map<CubeFace, TexelSurfacePlan> facePlans = cubePlans != null
                    && cubeIndex < cubePlans.size() ? cubePlans.get(cubeIndex) : null;
            Map<CubeFace, Integer> tints = bake != null ? bake.fallbackTints(cubeIndex) : null;
            List<Labelled> labelled;
            try {
                labelled = emitCube(cube, perFaceRendering, facePlans, resolution, tints);
            } catch (Throwable failure) {
                // DisplayEmitter resolves every face texture through
                // TextureMaterialResolver, whose block-name branch calls Material.isBlock().
                // That check is Bukkit-registry-backed, so a headless JVM cannot answer it
                // (ExceptionInInitializerError, then NoClassDefFoundError on every retry).
                // Only the cube that hit it is lost: report it honestly instead of failing
                // the op — and never let the Error reach the daemon's read loop.
                if (emitErrors == null) {
                    emitErrors = new JsonArray();
                }
                JsonObject error = new JsonObject();
                error.addProperty("cube", cubeIndex);
                error.addProperty("cubeName", cube.name());
                error.addProperty("error", String.valueOf(failure.getMessage()));
                error.addProperty("errorType", failure.getClass().getName());
                error.addProperty("reason", "the texture-material resolver needs Bukkit's block "
                        + "registry (Material.isBlock()), which only exists inside a running "
                        + "server; on the server this cube emits normally");
                emitErrors.add(error);
                DebugLogger.warning("[BakeOps] cube " + cubeIndex + " (" + cube.name()
                        + ") could not be emitted headless: " + failure);
                cubeIndex++;
                continue;
            }
            for (Labelled item : labelled) {
                boolean isTexel = MODE_TEXEL.equals(item.mode());
                if (isTexel) {
                    texels++;
                } else {
                    solid++;
                }
                total++;
                // Counted even when the list itself is suppressed, so the audit can
                // report the same numbers the preview draws without serializing them.
                int emission = Math.max(item.display().lightEmission(), brightnessFloor);
                maxEmission = Math.max(maxEmission, item.display().lightEmission());
                if (emission > 0) {
                    overrides++;
                }
                if (includeDisplays) {
                    displays.add(displayJson(item, cubeIndex, model, modelFile, store,
                            paletteByMaterial, sampleCache, brightnessFloor));
                }
            }
            cubeIndex++;
        }
        if (includeDisplays) {
            response.add("displays", displays);
        }
        response.addProperty("displayCount", total);
        response.addProperty("solidDisplays", solid);
        response.addProperty("texelDisplays", texels);
        response.addProperty("displaysComplete", emitErrors == null);
        if (emitErrors != null) {
            response.add("displayEmitErrors", emitErrors);
        }
        response.add("lighting", lighting(brightnessFloor, maxEmission, overrides, total));
        return new Stats(total, solid, texels, maxEmission, overrides);
    }

    /**
     * The lighting verdict for the whole emitted list, using the engine's own rule:
     * a display gets {@code setBrightness((min(max(lightEmission, floor), 15), 15))}
     * only when that value is above 0, so {@code overrideCount} counts the displays
     * the server actually pins and the rest inherit world light.
     */
    static JsonObject lighting(int floor, int maxEmission, int overrides, int total) {
        int effective = Math.min(Math.max(maxEmission, floor), 15);
        JsonObject json = new JsonObject();
        json.addProperty("maxEmission", maxEmission);
        json.addProperty("brightnessFloor", floor);
        json.addProperty("effectiveEmission", effective);
        json.addProperty("overrideCount", overrides);
        json.addProperty("anyOverride", overrides > 0);
        json.addProperty("displayCount", total);
        if (overrides > 0) {
            JsonObject brightness = new JsonObject();
            brightness.addProperty("block", effective);
            brightness.addProperty("sky", 15);
            json.add("displayBrightness", brightness);
            json.addProperty("skyAlways15", true);
        } else {
            json.add("displayBrightness", (JsonElement) null);
            json.addProperty("skyAlways15", false);
        }
        json.addProperty("rule", "VirtualBlockManager:609-612 — setBrightness is called only"
                + " when max(lightEmission, texelBrightness floor) > 0, always as"
                + " (min(level, 15), sky 15); otherwise the display inherits world light");
        return json;
    }

    /**
     * Run {@link DisplayEmitter} for one cube and report every display with the face
     * and tier that produced it — the routing the engine's spawn loop performs, just
     * observed one face at a time.
     */
    private static List<Labelled> emitCube(
            BakedCube cube,
            boolean perFaceRendering,
            Map<CubeFace, TexelSurfacePlan> facePlans,
            VirtualModel.Resolution resolution,
            Map<CubeFace, Integer> budgetFallbackTints
    ) {
        List<Labelled> out = new ArrayList<>();
        boolean hasTexelPlans = facePlans != null && !facePlans.isEmpty();
        if (!perFaceRendering && !hasTexelPlans && allFacesPresent(cube)) {
            // DisplayEmitter's single whole-block base display (its line 96 branch); an
            // AIR primary texture still short-circuits to zero geometry inside it.
            for (DisplayEmitter.EmittedDisplay display : DisplayEmitter.emitCube(
                    cube, false, null, resolution, budgetFallbackTints)) {
                out.add(new Labelled(display, cube, null, MODE_SOLID));
            }
            return out;
        }

        int textureWidth = resolution == null ? 16 : resolution.width();
        int textureHeight = resolution == null ? 16 : resolution.height();
        for (CubeFace face : CubeFace.values()) {
            BakedFace bakedFace = cube.faces().get(face);
            if (bakedFace == null) {
                continue; // omitted face in Blockbench: the emitter emits zero geometry
            }
            TexelSurfacePlan plan = hasTexelPlans ? facePlans.get(face) : null;
            Map<CubeFace, TexelSurfacePlan> singlePlan = null;
            if (plan != null) {
                singlePlan = new EnumMap<>(CubeFace.class);
                singlePlan.put(face, plan);
            }
            List<DisplayEmitter.EmittedDisplay> emitted = DisplayEmitter.emitCube(
                    singleFaceCube(cube, face), true, singlePlan, resolution, budgetFallbackTints);
            if (emitted.isEmpty()) {
                continue; // untextured or AIR-aliased face: intentionally nothing
            }
            String mode = plan != null ? MODE_TEXEL : plateMode(bakedFace, textureWidth,
                    textureHeight);
            for (DisplayEmitter.EmittedDisplay display : emitted) {
                out.add(new Labelled(display, cube, face, mode));
            }
        }
        return out;
    }

    /**
     * The tier a non-texel face renders on, decided by the same
     * {@link FaceUvAnalyzer#analyze(BakedFace, int, int)} call
     * {@code DisplayEmitter.plateDisplay} makes: a wrapping UV window becomes a grid
     * of {@code tile} displays, a half crop becomes {@code crop}, anything else a
     * single {@code plate}.
     */
    private static String plateMode(BakedFace bakedFace, int textureWidth, int textureHeight) {
        if (bakedFace.textureName() == null || bakedFace.textureName().isBlank()) {
            return MODE_PLATE;
        }
        FaceUvAnalyzer.UvPlan plan =
                FaceUvAnalyzer.analyze(bakedFace, textureWidth, textureHeight);
        return switch (plan.strategy()) {
            case TILE -> MODE_TILE;
            case CROP_HALF -> MODE_CROP;
            default -> MODE_PLATE;
        };
    }

    /**
     * The cube as a one-face cube: same name, transforms, primary texture, light
     * emission, bone index and geometry, so the emitter composes exactly the matrices
     * it would for the whole cube — only for this face.
     */
    private static BakedCube singleFaceCube(BakedCube cube, CubeFace face) {
        EnumMap<CubeFace, BakedFace> one = new EnumMap<>(CubeFace.class);
        one.put(face, cube.faces().get(face));
        return new BakedCube(cube.name(), cube.translation(), cube.leftRotation(), cube.scale(),
                cube.rightRotation(), one, cube.primaryTexture(), cube.lightEmission(),
                cube.boneIndex(), cube.geometry());
    }

    /** DisplayEmitter's own precondition for the whole-block fast path. */
    private static boolean allFacesPresent(BakedCube cube) {
        for (CubeFace face : CubeFace.values()) {
            BakedFace bakedFace = cube.faces().get(face);
            if (bakedFace == null || bakedFace.textureName() == null
                    || bakedFace.textureName().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject displayJson(
            Labelled item,
            int cubeIndex,
            VirtualModel model,
            File modelFile,
            TextureImageStore store,
            Map<Material, Integer> paletteByMaterial,
            Map<BakedFace, Integer> sampleCache,
            int brightnessFloor
    ) {
        DisplayEmitter.EmittedDisplay display = item.display();
        JsonObject json = new JsonObject();
        json.addProperty("cube", cubeIndex);
        json.addProperty("cubeName", item.cube() == null ? "" : item.cube().name());
        json.addProperty("mode", item.mode());
        // The viewer's tier histogram (bake.js) keys on `kind`: the whole-block base
        // display is the "cube" tier, a texel plate "texel", every other plate "face".
        // `mode` is the precise five-tier spelling; `kind` is its three-tier alias.
        json.addProperty("kind", MODE_TEXEL.equals(item.mode()) ? "texel"
                : MODE_SOLID.equals(item.mode()) ? "cube" : "face");
        json.addProperty("face", item.face() == null
                ? FACE_WHOLE : item.face().name().toLowerCase(Locale.ROOT));
        json.add("t", MobOps.Json.vec(display.translation().x, display.translation().y,
                display.translation().z));
        json.add("lr", MobOps.Json.vec(display.leftRotation().x, display.leftRotation().y,
                display.leftRotation().z, display.leftRotation().w));
        json.add("s", MobOps.Json.vec(display.scale().x, display.scale().y, display.scale().z));
        json.add("rr", MobOps.Json.vec(display.rightRotation().x, display.rightRotation().y,
                display.rightRotation().z, display.rightRotation().w));
        Material material = display.material();
        json.addProperty("material", material == null ? "AIR" : material.name());
        Integer paletteIndex = material == null ? null : paletteByMaterial.get(material);
        int rgb;
        String rgbSource;
        if (paletteIndex != null) {
            rgb = TexelPalette.rgb(paletteIndex);
            rgbSource = "palette";
        } else {
            Integer sampled = sampledRgb(model, modelFile, store, sampleCache, item);
            rgb = sampled == null ? FALLBACK_RGB : sampled;
            rgbSource = sampled == null ? "fallback" : "sampled";
        }
        json.addProperty("paletteIndex", paletteIndex == null ? -1 : paletteIndex);
        json.addProperty("sampleRgb", rgb);
        json.addProperty("rgb", rgb);
        json.addProperty("rgbSource", rgbSource);
        json.addProperty("lightEmission", display.lightEmission());
        // VirtualBlockManager:609-612 / 634-637 — setBrightness is only called when
        // max(lightEmission, texelBrightness floor) > 0, always as (level, sky 15).
        int emission = Math.max(display.lightEmission(), brightnessFloor);
        if (emission > 0) {
            JsonObject brightness = new JsonObject();
            brightness.addProperty("block", Math.min(emission, 15));
            brightness.addProperty("sky", 15);
            json.add("brightness", brightness);
        } else {
            json.add("brightness", (JsonElement) null);
        }
        // EmittedDisplay.blockData() is deliberately never called: it builds real
        // Bukkit BlockData, which a headless daemon cannot supply.
        return json;
    }

    /**
     * The display's colour when it is not a palette entry: the dominant opaque
     * pixel of that face's UV window, sampled through the same {@link TexelSampler}
     * bridge the baker reads (window + in-plane rotation affine over the PNG's
     * ARGB raster). A coarse 8x8 point sample, so this is representative rather
     * than exact — the browser badges it.
     */
    private static Integer sampledRgb(
            VirtualModel model,
            File modelFile,
            TextureImageStore store,
            Map<BakedFace, Integer> cache,
            Labelled item
    ) {
        BakedCube cube = item.cube();
        BakedFace face = cube == null || item.face() == null
                ? null : cube.faces().get(item.face());
        if (face != null && cache.containsKey(face)) {
            return cache.get(face);
        }
        Integer rgb = null;
        if (face != null) {
            rgb = dominantRgb(samplerFor(face, model, modelFile, store));
        }
        // The whole-block base display carries the cube's primary texture and no
        // single face, so sample whichever face references that texture.
        if (rgb == null && cube != null && cube.primaryTexture() != null
                && !cube.primaryTexture().isBlank()) {
            for (CubeFace candidate : CubeFace.values()) {
                BakedFace other = cube.faces().get(candidate);
                if (other == null || !cube.primaryTexture().equals(other.textureName())) {
                    continue;
                }
                rgb = dominantRgb(samplerFor(other, model, modelFile, store));
                if (rgb != null) {
                    break;
                }
            }
        }
        if (face != null) {
            cache.put(face, rgb);
        }
        return rgb;
    }

    /** The baker's own UV→PNG bridge, or {@code null} when the PNG is not on disk. */
    private static TexelSampler samplerFor(
            BakedFace face, VirtualModel model, File modelFile, TextureImageStore store) {
        if (face.textureName() == null || face.textureName().isBlank()) {
            return null;
        }
        TextureImageStore.TextureRaster raster = store.raster(face.textureName(), modelFile);
        return raster == null ? null
                : new TexelSampler(face, raster, model.resolution() == null
                        ? new VirtualModel.Resolution(16, 16) : model.resolution());
    }

    /** Most frequent opaque sampled pixel across the window; ties keep the first seen. */
    private static Integer dominantRgb(TexelSampler sampler) {
        if (sampler == null) {
            return null;
        }
        int steps = 8;
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        Integer best = null;
        int bestCount = 0;
        for (int gy = 0; gy < steps; gy++) {
            for (int gx = 0; gx < steps; gx++) {
                int argb = sampler.sample((gx + 0.5f) / steps, (gy + 0.5f) / steps);
                if (argb == 0) {
                    continue; // the sampler's cutout sentinel (alpha < 128)
                }
                int rgb = argb & 0xFFFFFF;
                int[] bucket = counts.computeIfAbsent(rgb, k -> new int[1]);
                bucket[0]++;
                if (bucket[0] > bestCount) {
                    bestCount = bucket[0];
                    best = rgb;
                }
            }
        }
        return best;
    }

    static JsonObject shading(String kind, boolean faceShade, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind);
        json.addProperty("faceShade", faceShade);
        json.addProperty("reason", reason);
        return json;
    }

    /** Shading plus the ambient-occlusion verdict the client applies to this axis. */
    static JsonObject shading(String kind, boolean faceShade, boolean ao, String reason) {
        JsonObject json = shading(kind, faceShade, reason);
        json.addProperty("ao", ao);
        return json;
    }

    /** The virtual engine's lighting rule, mirrored for the browser (never hardcoded). */
    static JsonObject brightnessPolicy(int floor) {
        JsonObject json = new JsonObject();
        json.addProperty("overrides", true);
        json.addProperty("floor", floor);
        json.addProperty("sky", 15);
        json.addProperty("source", "VirtualBlockManager:590-596,605-612,631-638");
        json.addProperty("rule", "the engine calls setBrightness(new Display.Brightness"
                + "(min(max(lightEmission, texelBrightnessFloor), 15), 15)) only when that"
                + " value is above 0; otherwise the display inherits world light");
        return json;
    }

    /**
     * The shading contract of a virtual {@code BlockDisplay}: it renders a real
     * block, so the client applies its per-face directional multipliers and smooth
     * lighting — the pack axis pins both of those off.
     */
    static JsonObject virtualBlockShading() {
        return shading("block-display", true, true,
                "a BlockDisplay renders a real block model, so the client applies its per-face"
                        + " directional multipliers (top 1.0 / x/z 0.6 / bottom 0.5) and ambient"
                        + " occlusion; pack content ships shade:false + ambientocclusion:false"
                        + " and stays flat");
    }

    /** Reverse {@code TexelPalette} lookup: the palette has no Material→index accessor. */
    private static Map<Material, Integer> paletteByMaterial() {
        Map<Material, Integer> map = new HashMap<>();
        for (int i = 0; i < TexelPalette.size(); i++) {
            Material material = TexelPalette.material(i);
            if (material != null) {
                map.putIfAbsent(material, i);
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Pack axis: the model JSON the resource pack would actually ship
    // ------------------------------------------------------------------

    /**
     * Serialize the model through the real pack writer and report its shading flags
     * read back out of the emitted JSON rather than hardcoded.
     *
     * <p>{@code ModelJsonWriter.writeBlock} is the right entry point for this op: it
     * is what {@code BlockModelAsset} uses for a pack block and keeps the geometry in
     * block space (origin shift +8, no display transform), while
     * {@code ModelJsonWriter.write} is the item-space variant the item/gun axis uses
     * (shift -8 plus the display transform). Both are emitted so the browser can
     * compare each axis against the artifact that axis really ships.</p>
     */
    static void appendPackModel(
            JsonObject response,
            VirtualModel model,
            ModelMeta.OriginMode originMode,
            String namespace,
            String key
    ) {
        String ns = namespace == null || namespace.isBlank() ? "mineplus" : namespace;
        try {
            List<String> warnings = new ArrayList<>();
            String blockJson = com.mineplus.pack.compile.ModelJsonWriter.writeBlock(
                    model, originMode, ns, warnings);
            JsonObject packModel = packModelJson(blockJson, "models/" + ns + "/" + key + ".json",
                    "ModelJsonWriter.writeBlock — the block-space entry point BlockModelAsset"
                            + " uses for a pack block", warnings);
            response.add("packModel", packModel);
            response.addProperty("packModelPath", packModel.get("path").getAsString());
            response.add("packModelWarnings", packModel.get("warnings").deepCopy());
            response.addProperty("packModelAmbientOcclusion",
                    packModel.get("ambientocclusion").getAsBoolean());
            response.addProperty("packModelShade", packModel.get("elementShade").getAsBoolean());

            List<String> itemWarnings = new ArrayList<>();
            String itemJson = com.mineplus.pack.compile.ModelJsonWriter.write(
                    model, originMode, ns, itemWarnings);
            response.add("packItemModel", packModelJson(itemJson,
                    "models/" + ns + "/" + key + ".item.json",
                    "ModelJsonWriter.write — the item-space entry point ModelAsset uses"
                            + " (origin shift -8 plus the display transform)", itemWarnings));
        } catch (Exception failure) {
            // Honest degradation: never reimplement the writer in the dev tool.
            response.add("packModel", (JsonElement) null);
            response.addProperty("packModelError",
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * One pack artifact: the verbatim JSON plus the shading flags parsed back out of
     * it. {@code ambientocclusion} defaults to true when the key is absent (vanilla's
     * own default) and {@code elementShade} is true unless every element pins
     * {@code shade:false} — an element with no {@code shade} key IS shaded.
     */
    private static JsonObject packModelJson(
            String json, String path, String variantNote, List<String> warnings) {
        JsonObject packModel = new JsonObject();
        packModel.addProperty("path", path);
        packModel.addProperty("json", json);
        packModel.addProperty("variant", variantNote);
        JsonArray warningArray = new JsonArray();
        for (String warning : warnings) {
            warningArray.add(warning);
        }
        packModel.add("warnings", warningArray);

        JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        boolean ambientOcclusion = Json.flag(
                root.has("ambientocclusion") ? root.get("ambientocclusion") : null, true);
        boolean elementShade = true;
        int elements = 0;
        if (root.has("elements") && root.get("elements").isJsonArray()) {
            JsonArray array = root.getAsJsonArray("elements");
            elements = array.size();
            if (elements > 0) {
                elementShade = false;
                for (JsonElement entry : array) {
                    if (!entry.isJsonObject()) {
                        continue;
                    }
                    JsonObject element = entry.getAsJsonObject();
                    if (!element.has("shade") || Json.flag(element.get("shade"), true)) {
                        elementShade = true;
                        break;
                    }
                }
            }
        }
        packModel.addProperty("ambientocclusion", ambientOcclusion);
        packModel.addProperty("elementShade", elementShade);
        packModel.addProperty("elementCount", elements);
        return packModel;
    }

    // ------------------------------------------------------------------
    // Texel-tool response fields (ported from the texel BakeDaemon)
    // ------------------------------------------------------------------

    private static JsonArray cubesJson(
            VirtualModel model, ModelMeta.TexelMode effectiveMode, Set<String> resolvedTextures) {
        JsonArray cubes = new JsonArray();
        for (BakedCube cube : model.cubes()) {
            JsonObject cj = new JsonObject();
            cj.addProperty("name", cube.name());
            cj.add("t", MobOps.Json.vec(cube.translation().x, cube.translation().y,
                    cube.translation().z));
            cj.add("lr", MobOps.Json.vec(cube.leftRotation().x, cube.leftRotation().y,
                    cube.leftRotation().z, cube.leftRotation().w));
            cj.add("s", MobOps.Json.vec(cube.scale().x, cube.scale().y, cube.scale().z));
            cj.add("rr", MobOps.Json.vec(cube.rightRotation().x, cube.rightRotation().y,
                    cube.rightRotation().z, cube.rightRotation().w));
            if (cube.primaryTexture() != null) {
                cj.addProperty("primaryTexture", cube.primaryTexture());
            }
            cj.addProperty("lightEmission", cube.lightEmission());
            JsonObject faces = new JsonObject();
            for (Map.Entry<CubeFace, BakedFace> entry : cube.faces().entrySet()) {
                BakedFace face = entry.getValue();
                JsonObject fj = new JsonObject();
                fj.addProperty("u1", face.u1());
                fj.addProperty("v1", face.v1());
                fj.addProperty("u2", face.u2());
                fj.addProperty("v2", face.v2());
                fj.addProperty("rotation", face.rotation());
                if (face.textureName() != null) {
                    fj.addProperty("texture", face.textureName());
                    boolean resolvable = resolvedTextures.contains(face.textureName());
                    FaceUvAnalyzer.UvPlan plan =
                            FaceUvAnalyzer.analyze(face, effectiveMode, resolvable);
                    fj.addProperty("strategy", plan.strategy().name());
                }
                faces.add(entry.getKey().name().toLowerCase(Locale.ROOT), fj);
            }
            cj.add("faces", faces);
            cubes.add(cj);
        }
        return cubes;
    }

    private static JsonObject texelJson(TexelBakeResult bake) {
        JsonObject tj = new JsonObject();
        tj.addProperty("enabled", bake.enabled());
        tj.addProperty("mode", bake.mode().name());
        tj.addProperty("detail", bake.detail().name());
        tj.addProperty("facesBaked", bake.facesBaked());
        tj.addProperty("facesTotal", bake.facesTotal());
        tj.addProperty("totalPlates", bake.totalPlates());
        tj.addProperty("maxPlatesOnFace", bake.maxPlatesOnFace());
        tj.addProperty("faceBudgetFallbacks", bake.faceBudgetFallbacks());
        tj.addProperty("instanceBudgetFallbacks", bake.instanceBudgetFallbacks());
        tj.addProperty("bakeTimeMs", bake.bakeTimeNanos() / 1_000_000.0);
        tj.addProperty("effectiveMaxPlatesPerFace", bake.effectiveMaxPlatesPerFace());
        tj.addProperty("effectiveMaxPlatesPerInstance", bake.effectiveMaxPlatesPerInstance());
        tj.addProperty("occludedCells", bake.occludedCells());
        tj.add("gridHistogram", intMapJson(bake.gridHistogram()));
        tj.add("paletteUsage", intMapJson(bake.paletteUsage()));

        JsonArray plans = new JsonArray();
        List<Map<CubeFace, TexelSurfacePlan>> cubePlans = bake.cubePlans();
        for (int cubeIndex = 0; cubeIndex < cubePlans.size(); cubeIndex++) {
            for (Map.Entry<CubeFace, TexelSurfacePlan> entry : cubePlans.get(cubeIndex).entrySet()) {
                TexelSurfacePlan plan = entry.getValue();
                JsonObject pj = new JsonObject();
                pj.addProperty("cube", cubeIndex);
                pj.addProperty("face", entry.getKey().name().toLowerCase(Locale.ROOT));
                pj.addProperty("gridWidth", plan.gridWidth());
                pj.addProperty("gridHeight", plan.gridHeight());
                pj.addProperty("dominantPaletteIndex", plan.dominantPaletteIndex());
                pj.addProperty("dominantArea", plan.dominantArea());
                pj.addProperty("occludedCells", plan.occludedCells());
                pj.addProperty("cutoutCells", plan.cutoutCells());
                JsonArray plates = new JsonArray();
                for (TexelSurfacePlan.Rect rect : plan.plates()) {
                    JsonArray rj = new JsonArray();
                    rj.add(rect.x());
                    rj.add(rect.y());
                    rj.add(rect.width());
                    rj.add(rect.height());
                    rj.add(rect.paletteIndex());
                    plates.add(rj);
                }
                pj.add("plates", plates);
                plans.add(pj);
            }
        }
        tj.add("cubePlans", plans);
        return tj;
    }

    private static JsonArray paletteJson() {
        JsonArray palette = new JsonArray();
        for (int i = 0; i < TexelPalette.size(); i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("i", i);
            entry.addProperty("rgb", TexelPalette.rgb(i));
            entry.addProperty("name", TexelPalette.materialName(i));
            palette.add(entry);
        }
        return palette;
    }

    private static JsonObject intMapJson(Map<?, Integer> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<?, Integer> entry : map.entrySet()) {
            json.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        return json;
    }

    private static JsonObject metaJson(ModelMeta meta) {
        JsonObject json = new JsonObject();
        if (meta == null) {
            return json;
        }
        if (meta.originMode() != null) {
            json.addProperty("originMode", meta.originMode().name());
        }
        if (meta.collisionMode() != null) {
            json.addProperty("collisionMode", meta.collisionMode().name());
        }
        if (meta.texelMode() != null) {
            json.addProperty("texelMode", meta.texelMode().name());
        }
        if (meta.texelDetail() != null) {
            json.addProperty("texelDetail", meta.texelDetail().name());
        }
        if (meta.maxTexelPlatesPerFace() != null) {
            json.addProperty("maxTexelPlatesPerFace", meta.maxTexelPlatesPerFace());
        }
        if (meta.maxTexelPlatesPerInstance() != null) {
            json.addProperty("maxTexelPlatesPerInstance", meta.maxTexelPlatesPerInstance());
        }
        if (meta.texelBrightness() != null) {
            json.addProperty("texelBrightness", meta.texelBrightness());
        }
        return json;
    }

    // ------------------------------------------------------------------
    // Settings merge (ported semantics: request = global settings, meta wins per model)
    // ------------------------------------------------------------------

    /** Flat request keys plus the texel tool's nested {@code overrides} spelling. */
    private static JsonObject mergedRequest(JsonObject raw) {
        JsonObject nested = raw.has("overrides") && raw.get("overrides").isJsonObject()
                ? raw.getAsJsonObject("overrides") : new JsonObject();
        JsonObject merged = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : nested.entrySet()) {
            if (!"meta".equals(entry.getKey())) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            if (!"overrides".equals(entry.getKey())) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        if (raw.has("meta") && raw.get("meta").isJsonObject()) {
            merged.add("meta", raw.getAsJsonObject("meta"));
        } else if (nested.has("meta") && nested.get("meta").isJsonObject()) {
            merged.add("meta", nested.getAsJsonObject("meta"));
        }
        return merged;
    }

    private static ModelMeta mergeMeta(ModelMeta base, JsonObject overrides) {
        if (overrides == null || overrides.size() == 0) {
            return base;
        }
        return new ModelMeta(
                str(overrides, "originMode", null) != null
                        ? ModelMeta.OriginMode.fromKey(str(overrides, "originMode", null), null)
                        : base.originMode(),
                str(overrides, "collisionMode", null) != null
                        ? ModelMeta.CollisionMode.fromKey(str(overrides, "collisionMode", null), null)
                        : base.collisionMode(),
                base.autoplay(),
                str(overrides, "texelMode", null) != null
                        ? ModelMeta.TexelMode.fromKey(str(overrides, "texelMode", null), null)
                        : base.texelMode(),
                str(overrides, "texelDetail", null) != null
                        ? ModelMeta.TexelDetail.fromKey(str(overrides, "texelDetail", null), null)
                        : base.texelDetail(),
                overrideInt(overrides, "maxTexelPlatesPerFace", base.maxTexelPlatesPerFace()),
                overrideInt(overrides, "maxTexelPlatesPerInstance", base.maxTexelPlatesPerInstance()),
                overrideInt(overrides, "texelBrightness", base.texelBrightness()),
                base.display(),
                str(overrides, "itemParent", base.itemParent()));
    }

    /**
     * Explicitly-typed Integer override: a ternary mixing {@code int} and
     * {@code Integer} would binary-promote to {@code int} and unbox a null base
     * value — the exact NPE this helper exists to avoid.
     */
    private static Integer overrideInt(JsonObject overrides, String name, Integer base) {
        if (!overrides.has(name) || overrides.get(name).isJsonNull()) {
            return base;
        }
        try {
            return overrides.get(name).getAsInt();
        } catch (Exception malformed) {
            return base;
        }
    }

    private static ModelMeta.OriginMode resolveOriginMode(
            JsonObject request, ModelMeta meta, VirtualModel model) {
        String requested = str(request, "originMode", null);
        if (requested == null && meta.originMode() != null) {
            requested = meta.originMode().name();
        }
        ModelMeta.OriginMode mode = requested != null
                ? ModelMeta.OriginMode.fromKey(requested, null) : null;
        if (mode == null || mode == ModelMeta.OriginMode.AUTO) {
            mode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());
        }
        return mode;
    }

    private static File textureRoot(JsonObject request, File modelFile) {
        String explicit = str(request, "textureRoot", null);
        if (explicit != null && !explicit.isBlank()) {
            File root = new File(explicit);
            if (root.isDirectory()) {
                return root;
            }
        }
        return modelFile.getParentFile();
    }

    private static File resolvedTexturePath(TextureImageStore store, String name, File modelFile) {
        String normalized = normalizeTextureName(name);
        if (normalized.isEmpty()) {
            return null;
        }
        if (modelFile.getParentFile() != null) {
            File adjacent = new File(modelFile.getParentFile(), normalized + ".png");
            if (adjacent.isFile()) {
                return adjacent;
            }
        }
        File rooted = store.rootFolderFile(normalized);
        return rooted != null && rooted.isFile() ? rooted : null;
    }

    /** Mirrors {@code TextureImageStore.normalize}: basename, extensions, namespace stripped. */
    private static String normalizeTextureName(String name) {
        if (name == null) {
            return "";
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }
        if (key.endsWith(".mcmeta")) {
            key = key.substring(0, key.length() - 7);
        }
        if (key.contains(":")) {
            key = key.substring(key.lastIndexOf(':') + 1);
        }
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf('/') + 1);
        }
        return key;
    }

    private static byte[] pngBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception failure) {
            DebugLogger.warning("asset-lab: could not re-encode texture preview: " + failure);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Tolerant JSON reads (shared implementation in Json.java)
    // ------------------------------------------------------------------

    private static JsonObject object(JsonObject o, String name) {
        return Json.object(o, name);
    }

    private static String str(JsonObject o, String name, String fallback) {
        return Json.str(o, name, fallback);
    }

    private static boolean bool(JsonObject o, String name, boolean fallback) {
        return Json.bool(o, name, fallback);
    }

    private static int intg(JsonObject o, String name, int fallback) {
        return Json.intg(o, name, fallback);
    }

    private static double dbl(JsonObject o, String name, double fallback) {
        return Json.dbl(o, name, fallback);
    }
}
