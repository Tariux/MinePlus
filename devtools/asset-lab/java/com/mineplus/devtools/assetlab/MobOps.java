package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.mob.custom.model.BbModelParser;
import com.mineplus.infrastructure.mob.custom.model.JavaJsonParser;
import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelFormatException;
import com.mineplus.infrastructure.mob.custom.model.ModelTexture;
import com.mineplus.infrastructure.mob.custom.render.ModelBundle;
import com.mineplus.infrastructure.mob.custom.render.ModelBundleCompiler;
import com.mineplus.infrastructure.mob.custom.render.PackModelEmitter;
import com.mineplus.infrastructure.mob.custom.render.PngUtil;
import com.mineplus.infrastructure.mob.custom.render.VariantDatapackWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/**
 * The daemon's {@code mob} op: parse + validate + pack-compile one custom-mob
 * presentation through the real pipeline classes ({@link BbModelParser},
 * {@link JavaJsonParser}, {@link ModelBundleCompiler}, {@link PackModelEmitter}
 * over a {@link HeadlessPackApi}) and return everything the web viewer needs —
 * bones, cubes, texture windows, full animation curves, the compile report and
 * the per-bone pack pieces. The same classes the server runs, so what the
 * viewer validates is what the server compiles.
 *
 * <p>Input (one of):</p>
 * <pre>{@code
 * {"op":"mob","yml":".../evil_pig.yml"}
 * {"op":"mob","model":".../soldier.bbmodel","texture":".../soldier.png"}
 * }</pre>
 *
 * <p>A {@code yml} input is read with the canonical keys of the Core's
 * {@code MobYamlLoader} ({@code render.type/file/texture/scale}, {@code
 * hand-items}, {@code animations}, {@code hitbox}); legacy aliases are not
 * applied — the loader itself reports those at server load.</p>
 */
final class MobOps {

    private MobOps() {
    }

    static JsonObject mob(JsonObject request) throws Exception {
        String ymlPath = Json.str(request, "yml", null);
        String modelPath = Json.str(request, "model", null);
        String texturePath = Json.str(request, "texture", null);
        if ((ymlPath == null || ymlPath.isBlank()) && (modelPath == null || modelPath.isBlank())) {
            throw new IllegalArgumentException("the mob op needs 'yml' or 'model'");
        }

        Spec spec;
        if (ymlPath != null && !ymlPath.isBlank()) {
            spec = readSpec(new File(ymlPath));
        } else {
            spec = Spec.fromDirectFiles(new File(modelPath), texturePath == null ? null : new File(texturePath));
        }

        JsonObject response = new JsonObject();
        response.addProperty("source", spec.sourceLabel);
        response.add("spec", specJson(spec));

        if (spec.type == RenderKind.RETEXTURE_VANILLA) {
            appendRetexture(response, spec);
            return response;
        }
        if (spec.type == RenderKind.NONE) {
            response.addProperty("renderType", "NONE");
            response.addProperty("status", "OK");
            response.addProperty("summary", "vanilla look; no model to validate");
            return response;
        }

        ModelData model = parseModel(spec);
        response.addProperty("renderType", spec.type.name());
        if (spec.modelFile == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the spec declares " + spec.type.name()
                    + " but has no render.file to parse");
            return response;
        }
        if (model == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", spec.parseError == null ? "the model did not parse" : spec.parseError);
            return response;
        }
        appendModel(response, spec, model);
        return response;
    }

    // ------------------------------------------------------------------
    // Spec (mirrors MobYamlLoader's canonical keys)
    // ------------------------------------------------------------------

    enum RenderKind { NONE, RETEXTURE_VANILLA, BBMODEL, JAVA_JSON }

    static final class Spec {
        String id = "mineplus:untyped";
        String baseEntity = null;
        String displayName = null;
        boolean showName;
        RenderKind type = RenderKind.BBMODEL;
        File modelFile;
        File textureFile;
        String sourceLabel = "model";
        float scale = 1.0f;
        String fallback = "base_entity";
        Double hitboxWidth;
        Double hitboxHeight;
        final Map<String, String> handItems = new LinkedHashMap<>();
        final Map<String, String> animationMap = new LinkedHashMap<>();
        String parseError;

        String namespace() {
            int colon = id.indexOf(':');
            return colon > 0 ? id.substring(0, colon) : "mineplus";
        }

        String path() {
            int colon = id.indexOf(':');
            return colon > 0 ? id.substring(colon + 1) : id;
        }

        static Spec fromDirectFiles(File model, File texture) {
            Spec spec = new Spec();
            spec.type = model.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel")
                    ? RenderKind.BBMODEL : RenderKind.JAVA_JSON;
            spec.modelFile = model;
            spec.textureFile = texture;
            spec.sourceLabel = model.getName();
            spec.id = "mineplus:" + stripExtension(model.getName());
            return spec;
        }
    }

    static Spec readSpec(File yml) {
        Spec spec = new Spec();
        spec.sourceLabel = yml.getName();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yml);
        String rawId = yaml.getString("id");
        if (rawId == null || rawId.isBlank()) {
            rawId = stripExtension(yml.getName());
        }
        spec.id = rawId.trim().toLowerCase(Locale.ROOT);
        spec.baseEntity = yaml.getString("base-entity");
        spec.displayName = yaml.getString("display-name");
        spec.showName = yaml.getBoolean("show-name", false);

        String typeText = yaml.getString("render.type");
        if (typeText == null && yaml.isSet("render.file")) {
            typeText = "bbmodel";
        }
        if (typeText != null) {
            spec.type = switch (typeText.trim().toLowerCase(Locale.ROOT)) {
                case "retexture_vanilla", "retexture", "vanilla_texture", "texture" -> RenderKind.RETEXTURE_VANILLA;
                case "java_json", "json" -> RenderKind.JAVA_JSON;
                case "none" -> RenderKind.NONE;
                default -> RenderKind.BBMODEL;
            };
        }

        String dir = yml.getParentFile() != null ? yml.getParentFile().getAbsolutePath() : ".";
        String fileText = yaml.getString("render.file");
        String textureText = yaml.getString("render.texture");
        if (fileText != null && !fileText.isBlank()) {
            spec.modelFile = new File(dir, fileText);
        }
        if (textureText != null && !textureText.isBlank()) {
            spec.textureFile = new File(dir, textureText);
        }
        Double scale = yaml.isSet("render.scale") ? yaml.getDouble("render.scale") : null;
        if (scale != null && scale > 0.0) {
            spec.scale = scale.floatValue();
        }
        String fallbackText = yaml.getString("render.fallback");
        if (fallbackText != null && !fallbackText.isBlank()) {
            spec.fallback = fallbackText.trim().toLowerCase(Locale.ROOT);
        }
        ConfigurationSection hitbox = yaml.getConfigurationSection("hitbox");
        if (hitbox != null) {
            double width = hitbox.getDouble("width", 0.0);
            double height = hitbox.getDouble("height", 0.0);
            if (width > 0.0 && height > 0.0) {
                spec.hitboxWidth = width;
                spec.hitboxHeight = height;
            }
        }
        ConfigurationSection handItems = yaml.getConfigurationSection("hand-items");
        if (handItems != null) {
            for (String bone : handItems.getKeys(false)) {
                String item = handItems.getString(bone);
                if (item != null && !item.isBlank()) {
                    spec.handItems.put(bone.toLowerCase(Locale.ROOT), item.trim());
                }
            }
        }
        ConfigurationSection animations = yaml.getConfigurationSection("animations");
        if (animations != null) {
            for (String state : animations.getKeys(false)) {
                String clip = animations.getString(state);
                if (clip != null && !clip.isBlank()) {
                    spec.animationMap.put(state.toLowerCase(Locale.ROOT), clip.trim());
                }
            }
        }
        return spec;
    }

    // ------------------------------------------------------------------
    // Parse + compile
    // ------------------------------------------------------------------

    static ModelData parseModel(Spec spec) {
        if (spec.type == RenderKind.JAVA_JSON) {
            try {
                return JavaJsonParser.parse(spec.modelFile.getName(), Files.readAllBytes(spec.modelFile.toPath()));
            } catch (ModelFormatException malformed) {
                spec.parseError = malformed.getMessage();
                return null;
            } catch (IOException unreadable) {
                spec.parseError = "model file unreadable: " + unreadable.getMessage();
                return null;
            }
        }
        byte[] sibling = spec.textureFile != null && spec.textureFile.isFile()
                ? readFileOrNull(spec.textureFile) : null;
        try {
            return BbModelParser.parse(spec.modelFile.getName(), Files.readAllBytes(spec.modelFile.toPath()),
                    sibling == null ? null : reference -> sibling);
        } catch (ModelFormatException malformed) {
            spec.parseError = malformed.getMessage();
            return null;
        } catch (IOException unreadable) {
            spec.parseError = "model file unreadable: " + unreadable.getMessage();
            return null;
        }
    }

    private static void appendModel(JsonObject response, Spec spec, ModelData model) {
        HeadlessPackApi pack = new HeadlessPackApi();
        ModelBundle bundle = ModelBundleCompiler.compile(pack, spec.namespace(), spec.path(), model);

        response.addProperty("status", bundle.report().status().name());
        response.addProperty("summary", bundle.report().summary());
        response.addProperty("fidelity", bundle.fidelity().name());
        response.addProperty("textureWidth", model.textureWidth());
        response.addProperty("textureHeight", model.textureHeight());
        response.addProperty("presentable", bundle.presentable());
        response.add("issues", Json.stringArray(model.issues()));
        response.add("reasons", Json.stringArray(bundle.report().reasons()));

        // Spec-facing animation aliases that failed to bind — the exact check
        // a broken walk/idle report starts with.
        JsonArray unbound = new JsonArray();
        for (Map.Entry<String, String> alias : spec.animationMap.entrySet()) {
            boolean found = model.animations().containsKey(alias.getValue().toLowerCase(Locale.ROOT));
            if (!found) {
                JsonObject entry = new JsonObject();
                entry.addProperty("state", alias.getKey());
                entry.addProperty("clip", alias.getValue());
                entry.addProperty("found", false);
                unbound.add(entry);
            }
        }
        response.add("animationAliases", unbound);

        // Textures: base64 when the parser had pixels (embedded base64 payload
        // or sibling file), flagged unresolved when not.
        JsonArray textures = new JsonArray();
        for (int i = 0; i < model.textures().size(); i++) {
            ModelTexture texture = model.textures().get(i);
            JsonObject tj = new JsonObject();
            tj.addProperty("name", texture.name());
            tj.addProperty("width", texture.width());
            tj.addProperty("height", texture.height());
            tj.addProperty("resolved", texture.isResolved());
            byte[] png = texture.png();
            if (texture.isResolved() && PngUtil.isPng(png)) {
                tj.addProperty("pngBase64", Base64.getEncoder().encodeToString(png));
                tj.addProperty("bytes", png.length);
            }
            textures.add(tj);
        }
        response.add("textures", textures);

        response.add("bones", bonesJson(model));
        response.add("cubes", cubesJson(model));
        response.add("animations", animationsJson(model));

        JsonObject stats = new JsonObject();
        stats.addProperty("boneCount", model.boneCount());
        stats.addProperty("geometryBones", geometryBoneCount(model));
        stats.addProperty("maxBones", ModelBundleCompiler.MAX_BONES);
        stats.addProperty("cubeCount", cubeCount(model));
        stats.addProperty("pieceCount", bundle.pieceCount());
        stats.addProperty("displayEstimate", bundle.displayEstimate(spec.handItems.size()));
        response.add("stats", stats);

        JsonArray pieces = new JsonArray();
        for (int i = 0; i < model.boneCount(); i++) {
            for (PackModelEmitter.Piece piece : bundle.pieces(i)) {
                JsonObject pj = new JsonObject();
                pj.addProperty("bone", model.bone(i).name());
                pj.addProperty("boneIndex", i);
                pj.addProperty("componentPath", piece.componentPath());
                pj.add("offsetPx", Json.vec(piece.offsetPx().x, piece.offsetPx().y, piece.offsetPx().z));
                pieces.add(pj);
            }
        }
        response.add("pieces", pieces);

        JsonObject assets = new JsonObject();
        assets.addProperty("generated", pack.generatedAssets().size());
        assets.addProperty("displayModels", pack.displayModels().size());
        response.add("packAssets", assets);
        response.addProperty("statusLine", bundle.statusLine());
    }

    private static void appendRetexture(JsonObject response, Spec spec) {
        response.addProperty("renderType", "RETEXTURE_VANILLA");
        if (spec.textureFile == null || !spec.textureFile.isFile()) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the replacement texture file is missing");
            return;
        }
        byte[] png = readFileOrNull(spec.textureFile);
        int[] dims = PngUtil.dimensions(png);
        if (dims == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the replacement texture is not a readable PNG");
            return;
        }
        EntityType baseType = entityType(spec.baseEntity);
        if (baseType == null) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "base-entity '" + spec.baseEntity
                    + "' is not a known EntityType; the variant registry cannot be resolved");
            return;
        }
        String registry = VariantDatapackWriter.registryFor(baseType);
        int[] expected = VariantDatapackWriter.expectedTextureSize(baseType);
        JsonObject tj = new JsonObject();
        tj.addProperty("name", spec.textureFile.getName());
        tj.addProperty("width", dims[0]);
        tj.addProperty("height", dims[1]);
        tj.addProperty("resolved", true);
        tj.addProperty("pngBase64", Base64.getEncoder().encodeToString(png));
        JsonArray textures = new JsonArray();
        textures.add(tj);
        response.add("textures", textures);

        if (expected != null && (dims[0] != expected[0] || dims[1] != expected[1])) {
            response.addProperty("status", "BROKEN");
            response.addProperty("summary", "the " + spec.baseEntity.toLowerCase(Locale.ROOT)
                    + " texture must be " + expected[0] + "x" + expected[1]
                    + " (vanilla layout), got " + dims[0] + "x" + dims[1]);
            return;
        }
        if (registry == null) {
            response.addProperty("status", "REPAIRED");
            response.addProperty("summary", "no variant registry for " + spec.baseEntity
                    + "; the texture ships for servers that support one");
            return;
        }
        response.addProperty("status", "OK");
        response.addProperty("summary", "variant '" + registry + " " + spec.path() + "' (needs a 1.21.5+ server)");
        response.addProperty("variantRegistry", registry);
    }

    // ------------------------------------------------------------------
    // JSON serialization
    // ------------------------------------------------------------------

    private static JsonObject specJson(Spec spec) {
        JsonObject sj = new JsonObject();
        sj.addProperty("id", spec.id);
        sj.addProperty("baseEntity", spec.baseEntity);
        sj.addProperty("displayName", spec.displayName);
        sj.addProperty("showName", spec.showName);
        sj.addProperty("type", spec.type.name());
        sj.addProperty("scale", spec.scale);
        sj.addProperty("fallback", spec.fallback);
        if (spec.hitboxWidth != null) {
            JsonArray hitbox = new JsonArray();
            hitbox.add(spec.hitboxWidth);
            hitbox.add(spec.hitboxHeight);
            sj.add("hitbox", hitbox);
        }
        JsonObject handItems = new JsonObject();
        for (Map.Entry<String, String> entry : spec.handItems.entrySet()) {
            handItems.addProperty(entry.getKey(), entry.getValue());
        }
        sj.add("handItems", handItems);
        JsonObject animationMap = new JsonObject();
        for (Map.Entry<String, String> entry : spec.animationMap.entrySet()) {
            animationMap.addProperty(entry.getKey(), entry.getValue());
        }
        sj.add("animationMap", animationMap);
        if (spec.modelFile != null) {
            sj.addProperty("modelFile", spec.modelFile.getAbsolutePath());
        }
        if (spec.textureFile != null) {
            sj.addProperty("textureFile", spec.textureFile.getAbsolutePath());
        }
        return sj;
    }

    private static JsonArray bonesJson(ModelData model) {
        JsonArray bones = new JsonArray();
        for (int i = 0; i < model.boneCount(); i++) {
            ModelBone bone = model.bone(i);
            JsonObject bj = new JsonObject();
            bj.addProperty("index", i);
            bj.addProperty("name", bone.name());
            bj.addProperty("rawName", bone.rawName());
            bj.addProperty("parentIndex", bone.parentIndex());
            bj.add("pivot", Json.vec(bone.pivotX(), bone.pivotY(), bone.pivotZ()));
            bj.add("rest", Json.vec(bone.restRotationX(), bone.restRotationY(), bone.restRotationZ()));
            bj.addProperty("attachment", bone.attachment().name());
            bj.addProperty("cubeCount", bone.cubes().size());
            bj.addProperty("locator", bone.isLocator());
            bones.add(bj);
        }
        return bones;
    }

    private static JsonArray cubesJson(ModelData model) {
        JsonArray cubes = new JsonArray();
        for (int boneIndex = 0; boneIndex < model.boneCount(); boneIndex++) {
            for (ModelCube cube : model.bone(boneIndex).cubes()) {
                JsonObject cj = new JsonObject();
                cj.addProperty("bone", boneIndex);
                cj.add("from", Json.vec(cube.fromX(), cube.fromY(), cube.fromZ()));
                cj.add("to", Json.vec(cube.toX(), cube.toY(), cube.toZ()));
                cj.addProperty("inflate", cube.inflate());
                cj.addProperty("textureIndex", cube.textureIndex());
                JsonObject faces = new JsonObject();
                for (Map.Entry<String, ModelCube.Uv> entry : cube.faces().entrySet()) {
                    ModelCube.Uv uv = entry.getValue();
                    JsonObject fj = new JsonObject();
                    fj.addProperty("u1", uv.u1());
                    fj.addProperty("v1", uv.v1());
                    fj.addProperty("u2", uv.u2());
                    fj.addProperty("v2", uv.v2());
                    faces.add(entry.getKey(), fj);
                }
                cj.add("faces", faces);
                cubes.add(cj);
            }
        }
        return cubes;
    }

    private static JsonArray animationsJson(ModelData model) {
        JsonArray clips = new JsonArray();
        for (ModelAnimation clip : model.animations().values()) {
            JsonObject cj = new JsonObject();
            cj.addProperty("name", clip.name());
            cj.addProperty("rawName", clip.rawName());
            cj.addProperty("lengthSeconds", clip.lengthSeconds());
            cj.addProperty("loopMode", clip.loopMode().name());
            JsonObject tracks = new JsonObject();
            for (Map.Entry<String, ModelAnimation.BoneTrack> entry : clip.trackEntries().entrySet()) {
                JsonObject tj = new JsonObject();
                if (entry.getValue().rotation() != null) {
                    tj.add("rotation", curveJson(entry.getValue().rotation()));
                }
                if (entry.getValue().position() != null) {
                    tj.add("position", curveJson(entry.getValue().position()));
                }
                if (entry.getValue().scale() != null) {
                    tj.add("scale", curveJson(entry.getValue().scale()));
                }
                tracks.add(entry.getKey(), tj);
            }
            cj.add("tracks", tracks);
            clips.add(cj);
        }
        return clips;
    }

    /**
     * Curve key data through reflection: {@code ModelAnimation.Curve} exposes
     * only {@code keyCount()}/{@code sample()} publicly, and the viewer needs
     * the authored keys to replay the clip at 60 fps without a daemon round
     * trip per frame. Daemon-side only; the core classes stay untouched.
     */
    private static JsonObject curveJson(ModelAnimation.Curve curve) {
        JsonObject cj = new JsonObject();
        float[] times = curveField(curve, "times", float[].class);
        float[][] values = curveField(curve, "values", float[][].class);
        ModelAnimation.Interpolation[] interpolations =
                curveField(curve, "interpolations", ModelAnimation.Interpolation[].class);
        if (times == null || values == null) {
            // Degenerate fallback: a dense re-sample keeps the viewer playable
            // even if a future core refactor renames the fields.
            cj.addProperty("sampled", true);
            JsonArray sampledTimes = new JsonArray();
            JsonArray sampledValues = new JsonArray();
            float[] out = new float[3];
            for (int k = 0; k <= 32; k++) {
                float t = k / 32.0f;
                curve.sample(t, out);
                sampledTimes.add(t);
                JsonArray vj = new JsonArray();
                vj.add(out[0]);
                vj.add(out[1]);
                vj.add(out[2]);
                sampledValues.add(vj);
            }
            cj.add("times", sampledTimes);
            cj.add("values", sampledValues);
            return cj;
        }
        JsonArray timesJson = new JsonArray();
        JsonArray valuesJson = new JsonArray();
        JsonArray interpolationsJson = new JsonArray();
        for (int k = 0; k < times.length; k++) {
            timesJson.add(times[k]);
            JsonArray vj = new JsonArray();
            vj.add(values[k][0]);
            vj.add(values[k][1]);
            vj.add(values[k][2]);
            valuesJson.add(vj);
            interpolationsJson.add(interpolations != null && k < interpolations.length
                    && interpolations[k] != null ? interpolations[k].name() : "LINEAR");
        }
        cj.add("times", timesJson);
        cj.add("values", valuesJson);
        cj.add("interpolations", interpolationsJson);
        return cj;
    }

    @SuppressWarnings("unchecked")
    private static <T> T curveField(ModelAnimation.Curve curve, String name, Class<T> type) {
        try {
            java.lang.reflect.Field field = ModelAnimation.Curve.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(curve);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int geometryBoneCount(ModelData model) {
        int count = 0;
        for (int i = 0; i < model.boneCount(); i++) {
            if (!model.bone(i).cubes().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int cubeCount(ModelData model) {
        int count = 0;
        for (int i = 0; i < model.boneCount(); i++) {
            count += model.bone(i).cubes().size();
        }
        return count;
    }

    private static EntityType entityType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        int colon = upper.indexOf(':');
        if (colon >= 0) {
            upper = upper.substring(colon + 1);
        }
        try {
            return EntityType.valueOf(upper);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static byte[] readFileOrNull(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            return null;
        }
    }

    static final class Json {

        private Json() {
        }

        static String str(JsonObject o, String name, String fallback) {
            if (!o.has(name) || o.get(name).isJsonNull()) {
                return fallback;
            }
            try {
                return o.get(name).getAsString();
            } catch (Exception malformed) {
                return fallback;
            }
        }

        static JsonArray vec(float... values) {
            JsonArray array = new JsonArray();
            for (float value : values) {
                array.add(Float.isFinite(value) ? value : 0.0f);
            }
            return array;
        }

        static JsonArray stringArray(List<String> values) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return array;
        }
    }
}
