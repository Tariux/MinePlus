package com.mineplus.devtools.texel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.config.MineplusConfig;
import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.BbModelImporter;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.FaceUvAnalyzer;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelPalette;
import com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker;
import com.mineplus.infrastructure.virtual.texel.TexelSurfacePlan;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.infrastructure.virtual.voxel.RenderStrategySelector;
import com.mineplus.infrastructure.virtual.voxel.VoxelModelBake;
import com.mineplus.infrastructure.virtual.voxel.VoxelRenderingSettings;
import com.mineplus.infrastructure.virtual.voxel.VoxelSurfaceBaker;
import com.mineplus.util.DebugLogger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Headless bake daemon for the Texel/Voxel hot-reload dev tool.
 *
 * <p>Runs the real, compiled pipeline classes (importer, analyzers, texel/voxel bakers,
 * strategy selector) with zero production-code modifications — render parity with the
 * server is structural, not maintained by discipline. Speaks line-delimited JSON over
 * stdin/stdout: one request object per line in, one response object per line out.
 * Logs go to stderr (JUL default console handler), never to stdout.
 *
 * <p>Request:
 * <pre>{@code
 * {"id":1,"op":"bake","model":"E:/path/model.bbmodel",
 *  "textureRoot":"E:/path",              // optional extra PNG lookup root
 *  "overrides":{
 *    "texelEnabled":true,"texelMode":"AUTO","texelDetail":"FACE",
 *    "maxPlatesPerFace":96,"maxPlatesPerInstance":150,"maxGridEdge":64,
 *    "voxelEnabled":true,"voxelMode":"AUTO","maxVoxelDisplays":1024,
 *    "perFaceRendering":true,"originMode":"AUTO",
 *    "meta":{"texelMode":"ON",...}        // explicit per-model overrides over the .meta.json
 *  }}
 * }</pre>
 *
 * <p>The dev server (Node) owns this process: it recompiles the pipeline on source
 * changes, restarts the daemon, and proxies bake requests. The daemon itself never
 * watches anything — restart is the hot-reload mechanism.
 */
public final class BakeDaemon {

    private BakeDaemon() {
    }

    public static void main(String[] args) throws Exception {
        // DebugLogger is a no-op until initialized; initializing with additional-debug
        // logs on (and JUL's default stderr console handler) surfaces baker warnings
        // in the dev tool without ever polluting the stdout JSON channel.
        DebugLogger.init(new MineplusConfig(true), Logger.getLogger("mineplus-devtool"));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonObject response;
            try {
                response = handle(JsonParser.parseString(line).getAsJsonObject());
            } catch (Exception failure) {
                response = new JsonObject();
                response.addProperty("ok", false);
                response.addProperty("error", String.valueOf(failure.getMessage()));
                response.addProperty("errorType", failure.getClass().getSimpleName());
                failure.printStackTrace();
            }
            out.write(response.toString());
            out.write('\n');
            out.flush();
        }
    }

    private static JsonObject handle(JsonObject request) {
        String op = str(request, "op", "");
        if ("ping".equals(op)) {
            JsonObject pong = ok(request);
            pong.addProperty("pong", true);
            return pong;
        }
        if (!"bake".equals(op)) {
            throw new IllegalArgumentException("unknown op '" + op + "'");
        }
        return bake(request);
    }

    private static JsonObject ok(JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        if (request.has("id") && !request.get("id").isJsonNull()) {
            response.add("id", request.get("id"));
        }
        return response;
    }

    private static JsonObject bake(JsonObject request) {
        String modelPath = str(request, "model", null);
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException("missing 'model' path");
        }
        File modelFile = new File(modelPath);
        if (!modelFile.isFile()) {
            throw new IllegalArgumentException("model file not found: " + modelPath);
        }

        JsonObject o = request.has("overrides") && request.get("overrides").isJsonObject()
                ? request.getAsJsonObject("overrides") : new JsonObject();
        JsonObject metaOverrides = o.has("meta") && o.get("meta").isJsonObject()
                ? o.getAsJsonObject("meta") : null;

        String key = modelFile.getName();
        int dot = key.lastIndexOf('.');
        if (dot > 0) {
            key = key.substring(0, dot);
        }

        VirtualModel model = BbModelImporter.parse(key, modelFile, Logger.getLogger("mineplus-devtool"));
        if (model == null) {
            throw new IllegalStateException("BbModelImporter rejected '" + modelPath + "'");
        }

        // Settings: request overrides are the *global* settings; the model's
        // .meta.json (plus explicit request-level meta overrides) still take
        // precedence per pipeline semantics.
        ModelMeta fileMeta = ModelMeta.load(modelFile);
        ModelMeta meta = mergeMeta(fileMeta, metaOverrides);

        TexelBakingSettings texelSettings = new TexelBakingSettings(
                bool(o, "texelEnabled", true),
                ModelMeta.TexelMode.fromKey(str(o, "texelMode", null), ModelMeta.TexelMode.AUTO),
                ModelMeta.TexelDetail.fromKey(str(o, "texelDetail", null), ModelMeta.TexelDetail.FACE),
                intg(o, "maxPlatesPerFace", 96),
                intg(o, "maxPlatesPerInstance", 150),
                intg(o, "maxGridEdge", 64));
        VoxelRenderingSettings voxelSettings = new VoxelRenderingSettings(
                bool(o, "voxelEnabled", true),
                ModelMeta.VoxelMode.fromKey(str(o, "voxelMode", null), ModelMeta.VoxelMode.AUTO),
                intg(o, "maxVoxelDisplays", 1024));
        VirtualRenderingSettings defaults = VirtualRenderingSettings.defaults();
        VirtualRenderingSettings renderingSettings = new VirtualRenderingSettings(
                defaults.collisionMode(),
                defaults.collisionEpsilon(),
                defaults.collisionNonAirPolicy(),
                defaults.rotationSnap(),
                defaults.rotationSnapThresholdDegrees(),
                bool(o, "perFaceRendering", defaults.perFaceRendering()),
                defaults.originMode());

        ModelMeta.OriginMode originMode = resolveOriginMode(o, meta, model);

        TextureImageStore store = new TextureImageStore(textureRoot(request, modelFile));

        // Texture resolvability scan (also warms the shared raster cache), matching
        // the voxel baker's own pre-scan.
        Set<String> resolvedTextures = new HashSet<>();
        JsonArray texturesJson = new JsonArray();
        for (String textureName : model.textureNames()) {
            TextureImageStore.TextureRaster raster = store.raster(textureName, modelFile);
            boolean resolved = raster != null;
            if (resolved) {
                resolvedTextures.add(textureName);
            }
            JsonObject tj = new JsonObject();
            tj.addProperty("name", textureName);
            tj.addProperty("resolved", resolved);
            if (resolved) {
                tj.addProperty("width", raster.width());
                tj.addProperty("height", raster.height());
                tj.addProperty("path", texturePath(store, textureName, modelFile));
            }
            texturesJson.add(tj);
        }

        TexelBakeResult texelBake = TexelSurfaceBaker.bakeModel(
                model, meta, modelFile, store, texelSettings);
        VoxelModelBake voxelBake = VoxelSurfaceBaker.bakeModel(
                model, meta, modelFile, store, voxelSettings, renderingSettings,
                texelBake, originMode);

        JsonObject response = ok(request);
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
        response.add("cubes", cubesJson(model, texelSettings.effectiveMode(meta), resolvedTextures));
        response.add("texel", texelJson(texelBake));
        response.add("voxel", voxelJson(voxelBake));
        return response;
    }

    private static ModelMeta.OriginMode resolveOriginMode(
            JsonObject overrides, ModelMeta meta, VirtualModel model) {
        String requested = str(overrides, "originMode", null);
        if (requested == null && meta != null && meta.originMode() != null) {
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

    private static String texturePath(TextureImageStore store, String name, File modelFile) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        File adjacent = new File(modelFile.getParentFile(), normalized + ".png");
        if (adjacent.isFile()) {
            return adjacent.getAbsolutePath();
        }
        return null;
    }

    private static ModelMeta mergeMeta(ModelMeta base, JsonObject overrides) {
        if (overrides == null || overrides.size() == 0) {
            return base;
        }
        return new ModelMeta(
                str(overrides, "originMode", null) != null
                        ? ModelMeta.OriginMode.fromKey(str(overrides, "originMode", null), null) : base.originMode(),
                str(overrides, "collisionMode", null) != null
                        ? ModelMeta.CollisionMode.fromKey(str(overrides, "collisionMode", null), null)
                        : base.collisionMode(),
                base.autoplay(),
                str(overrides, "texelMode", null) != null
                        ? ModelMeta.TexelMode.fromKey(str(overrides, "texelMode", null), null) : base.texelMode(),
                str(overrides, "texelDetail", null) != null
                        ? ModelMeta.TexelDetail.fromKey(str(overrides, "texelDetail", null), null)
                        : base.texelDetail(),
                overrideInt(overrides, "maxTexelPlatesPerFace", base.maxTexelPlatesPerFace()),
                overrideInt(overrides, "maxTexelPlatesPerInstance", base.maxTexelPlatesPerInstance()),
                overrideInt(overrides, "texelBrightness", base.texelBrightness()),
                str(overrides, "voxelMode", null) != null
                        ? ModelMeta.VoxelMode.fromKey(str(overrides, "voxelMode", null), null) : base.voxelMode(),
                overrideInt(overrides, "maxVoxelDisplays", base.maxVoxelDisplays()));
    }

    /**
     * Explicitly-typed Integer override: a ternary mixing {@code int} and
     * {@code Integer} would binary-promote to {@code int} and unbox a null
     * base value — the exact NPE this helper exists to avoid.
     */
    private static Integer overrideInt(JsonObject overrides, String name, Integer base) {
        if (!has(overrides, name)) {
            return base;
        }
        try {
            return overrides.get(name).getAsInt();
        } catch (Exception malformed) {
            return base;
        }
    }

    /**
     * Serializes the model's {@code .meta.json} overrides (omitting absent
     * fields) so the dev tool can apply the model's specific default settings
     * on load; an empty object means "no meta file — use standard defaults".
     */
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
        if (meta.voxelMode() != null) {
            json.addProperty("voxelMode", meta.voxelMode().name());
        }
        if (meta.maxVoxelDisplays() != null) {
            json.addProperty("maxVoxelDisplays", meta.maxVoxelDisplays());
        }
        return json;
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

    private static JsonArray cubesJson(
            VirtualModel model, ModelMeta.TexelMode effectiveMode, Set<String> resolvedTextures) {
        JsonArray cubes = new JsonArray();
        for (BakedCube cube : model.cubes()) {
            JsonObject cj = new JsonObject();
            cj.addProperty("name", cube.name());
            cj.add("t", vec(cube.translation().x, cube.translation().y, cube.translation().z));
            cj.add("lr", quat(cube.leftRotation()));
            cj.add("s", vec(cube.scale().x, cube.scale().y, cube.scale().z));
            cj.add("rr", quat(cube.rightRotation()));
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
                    FaceUvAnalyzer.UvPlan plan = FaceUvAnalyzer.analyze(face, effectiveMode, resolvable);
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

    private static JsonObject voxelJson(VoxelModelBake bake) {
        JsonObject vj = new JsonObject();
        vj.addProperty("strategy", bake.strategy().name());
        vj.addProperty("rationale", bake.rationale());
        vj.addProperty("occupiedVoxels", bake.occupiedVoxels());
        vj.addProperty("surfaceVoxels", bake.surfaceVoxels());
        vj.addProperty("culledInteriorVoxels", bake.culledInteriorVoxels());
        vj.addProperty("bakeTimeMs", bake.bakeTimeNanos() / 1_000_000.0);
        vj.add("paletteUsage", intMapJson(bake.paletteUsage()));
        JsonArray runs = new JsonArray();
        for (VoxelModelBake.VoxelRun run : bake.runs()) {
            JsonArray rj = new JsonArray();
            rj.add(finite(run.x()));
            rj.add(finite(run.y()));
            rj.add(finite(run.z()));
            rj.add(run.lengthX());
            rj.add(run.widthZ());
            rj.add(run.paletteIndex());
            rj.add(run.lightEmission());
            runs.add(rj);
        }
        vj.add("runs", runs);
        return vj;
    }

    private static JsonObject intMapJson(Map<? extends Object, Integer> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<? extends Object, Integer> entry : map.entrySet()) {
            json.addProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
        return json;
    }

    private static JsonArray vec(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(finite(x));
        array.add(finite(y));
        array.add(finite(z));
        return array;
    }

    private static JsonArray quat(org.joml.Quaternionf q) {
        JsonArray array = new JsonArray();
        array.add(finite(q.x));
        array.add(finite(q.y));
        array.add(finite(q.z));
        array.add(finite(q.w));
        return array;
    }

    /**
     * JSON-safe float: Gson serializes NaN/Infinity as bare tokens that
     * standard JSON parsers reject, and some models carry degenerate cubes
     * (zero-length quaternion normalizations). Non-finite values become 0 so
     * the response always parses; the affected cube renders degenerate, which
     * is itself a useful diagnostic.
     */
    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static boolean has(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull();
    }

    private static String str(JsonObject o, String name, String fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsString();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String name, boolean fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsBoolean();
        } catch (Exception malformed) {
            return fallback;
        }
    }

    private static int intg(JsonObject o, String name, int fallback) {
        if (!has(o, name)) {
            return fallback;
        }
        try {
            return o.get(name).getAsInt();
        } catch (Exception malformed) {
            return fallback;
        }
    }
}
