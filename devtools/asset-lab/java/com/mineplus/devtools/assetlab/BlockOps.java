package com.mineplus.devtools.assetlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.BbModelImporter;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.animation.Keyframe;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.util.DebugLogger;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * The daemon's {@code block} op: run the real block pipeline classes
 * ({@link BbModelImporter}, {@link ModelMeta}, {@link TextureImageStore}) on
 * one {@code .bbmodel} and return everything the web viewer needs — cubes with
 * their baked transforms, per-face UV windows, texture resolvability with the
 * actual PNGs, the resolved origin mode and the model's animation clips.
 *
 * <p>Deliberately <b>no texel baking</b> — surface baking is the texel tool's
 * domain; this op validates presentation and mechanism (origin anchoring,
 * collision mode, autoplay clips, texture windows) the way the server's virtual
 * block system consumes them. Kept fully separate from the texel devtool.</p>
 *
 * <p>Request: {@code {"op":"block","model":".../model.bbmodel","textureRoot":"..."}}
 * (textureRoot optional, defaults to the model's folder).</p>
 */
final class BlockOps {

    private BlockOps() {
    }

    static JsonObject block(JsonObject request) throws Exception {
        String modelPath = MobOps.Json.str(request, "model", null);
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException("the block op needs 'model'");
        }
        File modelFile = new File(modelPath);
        if (!modelFile.isFile()) {
            throw new IllegalArgumentException("model file not found: " + modelPath);
        }

        String key = MobOps.stripExtension(modelFile.getName());
        VirtualModel model = BbModelImporter.parse(key, modelFile, Logger.getLogger("mineplus-devtool"));
        if (model == null) {
            JsonObject broken = new JsonObject();
            broken.addProperty("file", modelFile.getAbsolutePath());
            broken.addProperty("status", "BROKEN");
            broken.addProperty("summary", "BbModelImporter rejected the file — it is not a valid"
                    + " .bbmodel (check the server log lines emitted on stderr)");
            return broken;
        }

        ModelMeta fileMeta = ModelMeta.load(modelFile);
        ModelMeta.OriginMode originMode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());

        String textureRootText = MobOps.Json.str(request, "textureRoot", null);
        File textureRoot = textureRootText != null && !textureRootText.isBlank()
                && new File(textureRootText).isDirectory() ? new File(textureRootText)
                : modelFile.getParentFile();
        TextureImageStore store = new TextureImageStore(textureRoot);

        Set<String> resolvedTextures = new HashSet<>();
        JsonArray texturesJson = new JsonArray();
        for (String textureName : model.textureNames()) {
            BufferedImage image = store.texture(textureName, modelFile);
            boolean resolved = image != null;
            if (resolved) {
                resolvedTextures.add(textureName);
            }
            JsonObject tj = new JsonObject();
            tj.addProperty("name", textureName);
            tj.addProperty("resolved", resolved);
            if (resolved) {
                tj.addProperty("width", image.getWidth());
                tj.addProperty("height", image.getHeight());
                File file = resolvedTexturePath(store, textureName, modelFile);
                if (file != null) {
                    tj.addProperty("path", file.getAbsolutePath());
                }
                byte[] png = pngBytes(image);
                if (png != null) {
                    tj.addProperty("pngBase64", Base64.getEncoder().encodeToString(png));
                }
            }
            texturesJson.add(tj);
        }

        JsonObject response = new JsonObject();
        response.addProperty("file", modelFile.getAbsolutePath());
        response.addProperty("key", key);
        response.addProperty("name", model.name());
        response.addProperty("modelFormat", model.modelFormat() == null ? "" : model.modelFormat());
        response.addProperty("originMode", originMode.name());
        response.addProperty("cubeCount", model.cubes().size());
        response.addProperty("animated", model.hasAnimations());
        response.addProperty("boneCount", model.bones().size());
        response.addProperty("hasMetaFile", !fileMeta.isEmpty());
        response.add("meta", metaJson(fileMeta));
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", model.resolution().width());
        resolution.addProperty("height", model.resolution().height());
        response.add("resolution", resolution);
        response.add("textures", texturesJson);
        response.add("cubes", cubesJson(model, resolvedTextures));
        response.add("animations", animationsJson(model));

        int unresolved = 0;
        for (int i = 0; i < texturesJson.size(); i++) {
            if (!texturesJson.get(i).getAsJsonObject().get("resolved").getAsBoolean()) {
                unresolved++;
            }
        }
        if (unresolved > 0) {
            response.addProperty("status", "REPAIRED");
            response.addProperty("summary", model.cubes().size() + " cube(s) import cleanly, but "
                    + unresolved + " texture(s) are not on disk — the client would render those"
                    + " faces with the missing texture");
        } else {
            response.addProperty("status", "OK");
            response.addProperty("summary", model.cubes().size() + " cube(s), every texture resolved"
                    + (model.hasAnimations() ? ", " + model.animations().size() + " clip(s)" : ""));
        }
        return response;
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

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
        json.add("autoplay", MobOps.Json.stringArray(meta.autoplay()));
        if (meta.texelBrightness() != null) {
            json.addProperty("texelBrightness", meta.texelBrightness());
        }
        return json;
    }

    private static JsonArray cubesJson(VirtualModel model, Set<String> resolvedTextures) {
        JsonArray cubes = new JsonArray();
        for (BakedCube cube : model.cubes()) {
            JsonObject cj = new JsonObject();
            cj.addProperty("name", cube.name());
            cj.add("t", vec(cube.translation().x, cube.translation().y, cube.translation().z));
            cj.add("lr", quat(cube.leftRotation().x, cube.leftRotation().y,
                    cube.leftRotation().z, cube.leftRotation().w));
            cj.add("s", vec(cube.scale().x, cube.scale().y, cube.scale().z));
            cj.add("rr", quat(cube.rightRotation().x, cube.rightRotation().y,
                    cube.rightRotation().z, cube.rightRotation().w));
            if (cube.primaryTexture() != null) {
                cj.addProperty("primaryTexture", cube.primaryTexture());
                cj.addProperty("primaryResolved", resolvedTextures.contains(cube.primaryTexture()));
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
                    fj.addProperty("resolved", resolvedTextures.contains(face.textureName()));
                }
                faces.add(entry.getKey().name().toLowerCase(Locale.ROOT), fj);
            }
            cj.add("faces", faces);
            cubes.add(cj);
        }
        return cubes;
    }

    /**
     * Full clip serialization — {@code AnimationClip} exposes every keyframe
     * publicly, so the viewer can replay block animations at 60 fps without a
     * daemon round trip per frame (no reflection needed, unlike the mob rig).
     */
    private static JsonArray animationsJson(VirtualModel model) {
        JsonArray clips = new JsonArray();
        for (AnimationClip clip : model.animations()) {
            JsonObject cj = new JsonObject();
            cj.addProperty("name", clip.name());
            cj.addProperty("lengthSeconds", clip.length());
            cj.addProperty("loopMode", clip.loop().name());
            JsonObject tracks = new JsonObject();
            for (Map.Entry<String, AnimationClip.BoneAnimation> entry : clip.animators().entrySet()) {
                JsonObject tj = new JsonObject();
                tj.add("rotation", channelJson(entry.getValue().rotation()));
                tj.add("position", channelJson(entry.getValue().position()));
                tj.add("scale", channelJson(entry.getValue().scale()));
                tracks.add(entry.getKey(), tj);
            }
            cj.add("tracks", tracks);
            clips.add(cj);
        }
        return clips;
    }

    private static JsonArray channelJson(List<Keyframe> keyframes) {
        JsonArray channel = new JsonArray();
        for (Keyframe keyframe : keyframes) {
            JsonObject kj = new JsonObject();
            kj.addProperty("time", keyframe.time());
            kj.add("value", vec(keyframe.x(), keyframe.y(), keyframe.z()));
            kj.addProperty("interpolation",
                    keyframe.interpolation() == null ? "LINEAR" : keyframe.interpolation().name());
            channel.add(kj);
        }
        return channel;
    }

    /**
     * Same-name lookup the texel tool uses: the PNG the store actually found,
     * wherever it found it (next to the model or in the root folder).
     */
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

    /** Mirrors {@code TextureImageStore.normalize}: basename, extensions and namespace stripped. */
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

    private static JsonArray vec(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(finite(x));
        array.add(finite(y));
        array.add(finite(z));
        return array;
    }

    private static JsonArray quat(float x, float y, float z, float w) {
        JsonArray array = new JsonArray();
        array.add(finite(x));
        array.add(finite(y));
        array.add(finite(z));
        array.add(finite(w));
        return array;
    }

    /**
     * JSON-safe float: Gson serializes NaN/Infinity as bare tokens that
     * standard JSON parsers reject. Non-finite values become 0 so the response
     * always parses; the affected cube renders degenerate, which is itself a
     * useful diagnostic.
     */
    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}
