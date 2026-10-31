package com.mineplus.infrastructure.mob.custom.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Blockbench generic project ({@code .bbmodel}) into an immutable
 * {@link ModelData}.
 *
 * <p>Handles the shapes real exports use: generic and bedrock cube fields
 * ({@code from/to} or {@code origin/size}), box-UV and per-face UVs, embedded
 * base64 textures or references resolved through a sibling resolver, the
 * recursive outliner group tree, locators and null objects as attachment
 * bones, and animations with per-key linear/step/catmullrom interpolation.
 * Everything the parser does not understand is recorded as an issue and
 * skipped — never a crash, never silent garbage.</p>
 */
public final class BbModelParser {

    /** Resolves an external texture reference (relative path or name) to PNG bytes. */
    public interface TextureResolver {
        byte[] resolve(String reference);
    }

    private BbModelParser() {
    }

    public static ModelData parse(String sourceName, byte[] bytes, TextureResolver textures) {
        if (bytes == null || bytes.length == 0) {
            throw new ModelFormatException("Model file is empty: " + sourceName);
        }
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new ModelFormatException("Model file is not JSON: " + sourceName, malformed);
        }
        if (!rootElement.isJsonObject()) {
            throw new ModelFormatException("Model root is not an object: " + sourceName);
        }
        return new Parse(sourceName, rootElement.getAsJsonObject(), textures).parse();
    }

    /** Reads PNG IHDR dimensions; {@code (-1, -1)} when not a PNG. */
    public static final class PngSize {

        private PngSize() {
        }

        public static boolean isPng(byte[] bytes) {
            return bytes != null && bytes.length > 24
                    && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        }

        public static int[] dimensions(byte[] png) {
            if (!isPng(png)) {
                return new int[]{-1, -1};
            }
            int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
            int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
            return new int[]{width, height};
        }
    }

    // ------------------------------------------------------------------
    // Per-parse state (no statics: parsers run concurrently and repeatedly)
    // ------------------------------------------------------------------

    private static final class Parse {

        private final String sourceName;
        private final JsonObject root;
        private final BbModelParser.TextureResolver textureResolver;
        private final List<String> issues = new ArrayList<>();
        private final List<ModelBone> bones = new ArrayList<>();
        private final Map<String, Integer> boneIndexByUuid = new HashMap<>();
        private final Map<String, Integer> elementBoneIndex = new HashMap<>();
        /** element uuid -> the cube parsed for it, in element order. */
        private final Map<String, List<ModelCube>> cubesByElement = new LinkedHashMap<>();
        private final Map<String, Boolean> molangWarned = new HashMap<>();
        private boolean pixelUvWarned;
        private int rotationBoneCounter;

        private Parse(String sourceName, JsonObject root, BbModelParser.TextureResolver textureResolver) {
            this.sourceName = sourceName;
            this.root = root;
            this.textureResolver = textureResolver;
        }

        private ModelData parse() {
            int textureWidth = 16;
            int textureHeight = 16;
            JsonObject resolution = root.getAsJsonObject("resolution");
            if (resolution != null) {
                textureWidth = Math.max(1, resolution.get("width").getAsInt());
                textureHeight = Math.max(1, resolution.get("height").getAsInt());
            }
            boolean boxUvDefault = root.has("meta") && root.get("meta").isJsonObject()
                    && root.getAsJsonObject("meta").has("box_uv")
                    && root.getAsJsonObject("meta").get("box_uv").getAsBoolean();

            List<ModelTexture> textures = parseTextures(textureWidth, textureHeight);

            Map<String, JsonObject> elementsByUuid = new LinkedHashMap<>();
            if (root.has("elements") && root.get("elements").isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray("elements")) {
                    if (entry.isJsonObject() && entry.getAsJsonObject().has("uuid")) {
                        elementsByUuid.put(entry.getAsJsonObject().get("uuid").getAsString(),
                                entry.getAsJsonObject());
                    }
                }
            }

            int rootIndex = bones.size();
            bones.add(new ModelBone("root", "root", -1, 0, 0, 0, 0, 0, 0,
                    List.of(), ModelBone.Attachment.NONE));
            if (root.has("outliner") && root.get("outliner").isJsonArray()) {
                walkOutliner(root.getAsJsonArray("outliner"), rootIndex, elementsByUuid);
            } else {
                for (String uuid : elementsByUuid.keySet()) {
                    elementBoneIndex.put(uuid, rootIndex);
                }
            }

            // Cubes per bone, element order preserved for determinism.
            Map<Integer, List<ModelCube>> cubesByBone = new LinkedHashMap<>();
            for (Map.Entry<String, JsonObject> entry : elementsByUuid.entrySet()) {
                String uuid = entry.getKey();
                JsonObject element = entry.getValue();
                String type = string(element, "type", "cube");
                if (type.equals("locator") || type.equals("null_object")) {
                    continue; // attachment bones, created by the outliner walk
                }
                if (type.equals("mesh")) {
                    issues.add(sourceName + ": mesh elements are not supported; convert them to cubes"
                            + " in Blockbench. (element '" + string(element, "name", uuid) + "')");
                    continue;
                }
                if (!type.equals("cube")) {
                    issues.add(sourceName + ": element type '" + type + "' is not supported; skipped. ("
                            + string(element, "name", uuid) + ")");
                    continue;
                }
                Integer boneIndex = elementBoneIndex.getOrDefault(uuid, rootIndex);
                ModelBone bone = bones.get(boneIndex);
                ModelCube cube;
                try {
                    cube = parseCube(element, boxUvDefault, bone, textures, textureWidth, textureHeight);
                } catch (RuntimeException malformed) {
                    issues.add(sourceName + ": element '" + string(element, "name", uuid)
                            + "' is malformed (" + malformed.getMessage() + "); skipped.");
                    continue;
                }
                if (cube == null) {
                    continue;
                }
                float[] rotation = elementRotation(element);
                if (rotation == null) {
                    cubesByBone.computeIfAbsent(boneIndex, k -> new ArrayList<>()).add(cube);
                } else {
                    // Bake the element rotation into a synthetic sub-bone: the
                    // client's element rotation is limited to one axis at ±45°,
                    // a bone rotation has neither limit.
                    float[] origin = floatArrayOr(element.get("origin"), 0.0f, 3);
                    while (origin.length < 3) {
                        origin = new float[]{0, 0, 0};
                    }
                    String subName = uniqueName(bone.name() + "_rot" + (rotationBoneCounter++));
                    int subIndex = bones.size();
                    bones.add(new ModelBone(subName, string(element, "name", uuid) + " (rotation)",
                            boneIndex, origin[0], origin[1], origin[2],
                            rotation[0], rotation[1], rotation[2], List.of(), ModelBone.Attachment.NONE));
                    // The cube is already relative to the parent bone's pivot; the
                    // sub-bone pivots at the absolute origin, so re-base by the
                    // origin as seen from the parent bone.
                    float[] originFromParent = new float[]{
                            origin[0] - bone.pivotX(), origin[1] - bone.pivotY(),
                            origin[2] - bone.pivotZ()};
                    cubesByBone.computeIfAbsent(subIndex, k -> new ArrayList<>())
                            .add(rebaseCube(cube, originFromParent));
                }
            }

            List<ModelBone> finished = new ArrayList<>(bones.size());
            for (int i = 0; i < bones.size(); i++) {
                ModelBone bone = bones.get(i);
                finished.add(new ModelBone(bone.name(), bone.rawName(), bone.parentIndex(),
                        bone.pivotX(), bone.pivotY(), bone.pivotZ(),
                        bone.restRotationX(), bone.restRotationY(), bone.restRotationZ(),
                        cubesByBone.getOrDefault(i, List.of()), bone.attachment()));
            }

            Map<String, ModelAnimation> animations = root.has("animations") && root.get("animations").isJsonArray()
                    ? parseAnimations(root.getAsJsonArray("animations"), finished)
                    : Map.of();

            return new ModelData(sourceName, textureWidth, textureHeight, finished, textures, animations, issues);
        }

        // ------------------------------------------------------------------
        // Outliner
        // ------------------------------------------------------------------

        private void walkOutliner(JsonArray outliner, int parentIndex, Map<String, JsonObject> elementsByUuid) {
            int counter = 0;
            for (JsonElement entry : outliner) {
                counter++;
                if (entry.isJsonPrimitive()) {
                    attachElement(entry.getAsString(), parentIndex, elementsByUuid);
                    continue;
                }
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject group = entry.getAsJsonObject();
                String uuid = string(group, "uuid", "");
                float[] pivot = floatArrayOr(group.get("origin"), 0.0f, 3);
                float[] rest = floatArrayOr(group.get("rotation"), 0.0f, 3);
                String rawName = string(group, "name", "group_" + counter);
                String name = uniqueName(ModelBone.sanitizeName(rawName, bones.size()));
                int index = bones.size();
                bones.add(new ModelBone(name, rawName, parentIndex,
                        pivot[0], pivot[1], pivot[2], rest[0], rest[1], rest[2],
                        List.of(), ModelBone.detectAttachment(rawName)));
                if (!uuid.isEmpty()) {
                    boneIndexByUuid.put(uuid, index);
                }
                if (group.has("children") && group.get("children").isJsonArray()) {
                    walkOutliner(group.getAsJsonArray("children"), index, elementsByUuid);
                }
            }
        }

        /**
         * An outliner child that is an element uuid: a cube attaches to its
         * group; a locator / null object becomes its own attachment bone.
         */
        private void attachElement(String uuid, int parentIndex, Map<String, JsonObject> elementsByUuid) {
            JsonObject element = elementsByUuid.get(uuid);
            if (element == null) {
                return;
            }
            String type = string(element, "type", "cube");
            if (type.equals("locator") || type.equals("null_object")) {
                float[] position = floatArrayOr(element.get("position"), 0.0f, 3);
                String rawName = string(element, "name", "locator");
                String name = uniqueName(ModelBone.sanitizeName(rawName, bones.size()));
                int index = bones.size();
                bones.add(new ModelBone(name, rawName, parentIndex,
                        position[0], position[1], position[2], 0, 0, 0,
                        List.of(), ModelBone.detectAttachment(rawName)));
                elementBoneIndex.put(uuid, index);
                return;
            }
            elementBoneIndex.put(uuid, parentIndex);
        }

        private String uniqueName(String candidate) {
            Set<String> taken = new HashSet<>();
            for (ModelBone bone : bones) {
                taken.add(bone.name());
            }
            String name = candidate;
            int suffix = 2;
            while (taken.contains(name)) {
                name = candidate + "_" + suffix++;
            }
            return name;
        }

        // ------------------------------------------------------------------
        // Textures
        // ------------------------------------------------------------------

        private List<ModelTexture> parseTextures(int width, int height) {
            List<ModelTexture> textures = new ArrayList<>();
            if (!root.has("textures") || !root.get("textures").isJsonArray()) {
                issues.add(sourceName + ": no textures embedded; a neutral placeholder will be substituted.");
                return textures;
            }
            for (JsonElement entry : root.getAsJsonArray("textures")) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject texture = entry.getAsJsonObject();
                String name = string(texture, "name", "texture_" + textures.size());
                int w = texture.has("width") && texture.get("width").isJsonPrimitive()
                        ? Math.max(1, texture.get("width").getAsInt()) : width;
                int h = texture.has("height") && texture.get("height").isJsonPrimitive()
                        ? Math.max(1, texture.get("height").getAsInt()) : height;
                byte[] png = null;
                String source = string(texture, "source", null);
                if (source != null && source.startsWith("data:image/")) {
                    int comma = source.indexOf(',');
                    if (comma >= 0) {
                        try {
                            png = Base64.getDecoder().decode(source.substring(comma + 1));
                        } catch (IllegalArgumentException malformed) {
                            issues.add(sourceName + ": texture '" + name + "' has undecodable base64 data.");
                        }
                    }
                } else if (source != null && textureResolver != null) {
                    png = textureResolver.resolve(source);
                    if (png == null) {
                        issues.add(sourceName + ": texture '" + name + "' references '" + source
                                + "' which could not be read; a placeholder will be substituted.");
                    }
                } else if (source != null) {
                    issues.add(sourceName + ": texture '" + name + "' has no embedded data and no resolver.");
                }
                boolean resolved = PngSize.isPng(png);
                textures.add(new ModelTexture(name, w, h, resolved ? png : null, resolved));
            }
            return textures;
        }

        // ------------------------------------------------------------------
        // Cubes
        // ------------------------------------------------------------------

        private ModelCube parseCube(JsonObject element, boolean boxUvDefault, ModelBone bone,
                                    List<ModelTexture> textures, int modelWidth, int modelHeight) {
            float[] from;
            float[] to;
            if (element.has("from") && element.get("from").isJsonArray()) {
                from = floatArrayOr(element.get("from"), 0.0f, 3);
                to = floatArrayOr(element.get("to"), 0.0f, 3);
            } else if (element.has("origin") && element.has("size")) {
                // Bedrock-project exports: origin corner + size.
                from = floatArrayOr(element.get("origin"), 0.0f, 3);
                float[] size = floatArrayOr(element.get("size"), 0.0f, 3);
                to = new float[]{from[0] + size[0], from[1] + size[1], from[2] + size[2]};
            } else {
                return null;
            }
            if (to[0] <= from[0] || to[1] <= from[1] || to[2] <= from[2]) {
                return null;
            }
            float inflate = element.has("inflate") && element.get("inflate").isJsonPrimitive()
                    ? element.get("inflate").getAsFloat() : 0.0f;

            float x1 = from[0] - bone.pivotX();
            float y1 = from[1] - bone.pivotY();
            float z1 = from[2] - bone.pivotZ();
            float x2 = to[0] - bone.pivotX();
            float y2 = to[1] - bone.pivotY();
            float z2 = to[2] - bone.pivotZ();

            int textureIndex = element.has("texture") && element.get("texture").isJsonPrimitive()
                    ? element.get("texture").getAsInt() : -1;
            if (textureIndex >= textures.size()) {
                textureIndex = textures.size() - 1;
            }

            Map<String, ModelCube.Uv> faces = new LinkedHashMap<>();
            boolean boxUv = boxUvDefault;
            if (element.has("box_uv") && element.get("box_uv").isJsonPrimitive()) {
                boxUv = element.get("box_uv").getAsBoolean();
            }
            if (boxUv) {
                float[] uv = floatArrayOr(element.get("uv_offset"), 0.0f, 2);
                float w = x2 - x1;
                float h = y2 - y1;
                float d = z2 - z1;
                float u = uv[0];
                float v = uv[1];
                // The vanilla box-UV cross layout: west | north | east | south in
                // the band below the top, top above the north face, bottom right
                // of the top — matching the vanilla ModelBox/skin sheet.
                faces.put("west", new ModelCube.Uv(u, v + d, u + d, v + d + h));
                faces.put("north", new ModelCube.Uv(u + d, v + d, u + d + w, v + d + h));
                faces.put("east", new ModelCube.Uv(u + d + w, v + d, u + d + w + d, v + d + h));
                faces.put("south", new ModelCube.Uv(u + d + w + d, v + d, u + d + w + d + w, v + d + h));
                faces.put("up", new ModelCube.Uv(u + d, v, u + d + w, v + d));
                faces.put("down", new ModelCube.Uv(u + d + w, v + d, u + d + w + w, v + 2 * d));
            } else if (element.has("faces") && element.get("faces").isJsonObject()) {
                JsonObject faceObject = element.getAsJsonObject("faces");
                // Blockbench stores per-face UVs normalized to their texture; the
                // pipeline's convention is texture pixels, so scale them here.
                ModelTexture referenced = textureIndex >= 0 && textureIndex < textures.size()
                        ? textures.get(textureIndex) : null;
                int texW = referenced != null ? referenced.width() : modelWidth;
                int texH = referenced != null ? referenced.height() : modelHeight;
                for (String direction : new String[]{"north", "south", "east", "west", "up", "down"}) {
                    JsonElement faceElement = faceObject.get(direction);
                    if (faceElement == null || !faceElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject face = faceElement.getAsJsonObject();
                    float[] uv = floatArrayOr(face.get("uv"), Float.NaN, 4);
                    if (uv.length < 4 || Float.isNaN(uv[0])) {
                        continue;
                    }
                    float max = Math.max(Math.max(Math.abs(uv[0]), Math.abs(uv[1])),
                            Math.max(Math.abs(uv[2]), Math.abs(uv[3])));
                    if (max > 1.001f) {
                        // Already pixel-space (an unusual export); keep as-is once warned.
                        if (!pixelUvWarned) {
                            pixelUvWarned = true;
                            issues.add(sourceName + ": per-face UVs are already pixel-space;"
                                    + " scaling skipped.");
                        }
                        faces.put(direction, new ModelCube.Uv(uv[0], uv[1], uv[2], uv[3]));
                    } else {
                        faces.put(direction, new ModelCube.Uv(uv[0] * texW, uv[1] * texH,
                                uv[2] * texW, uv[3] * texH));
                    }
                }
            }
            return new ModelCube(x1, y1, z1, x2, y2, z2, inflate, textureIndex, faces);
        }

        private static ModelCube rebaseCube(ModelCube cube, float[] origin) {
            return new ModelCube(
                    cube.fromX() - origin[0], cube.fromY() - origin[1], cube.fromZ() - origin[2],
                    cube.toX() - origin[0], cube.toY() - origin[1], cube.toZ() - origin[2],
                    cube.inflate(), cube.textureIndex(), cube.faces());
        }

        /** The element rotation as {rx, ry, rz} degrees, or {@code null} when none. */
        private float[] elementRotation(JsonObject element) {
            JsonElement rotation = element.get("rotation");
            if (rotation == null) {
                return null;
            }
            if (rotation.isJsonObject()) {
                // Java block-model style: {axis, angle, origin}.
                String axis = string(rotation.getAsJsonObject(), "axis", "");
                float angle = rotation.getAsJsonObject().has("angle")
                        && rotation.getAsJsonObject().get("angle").isJsonPrimitive()
                        ? rotation.getAsJsonObject().get("angle").getAsFloat() : 0.0f;
                if (Math.abs(angle) < 1.0e-4f) {
                    return null;
                }
                return switch (axis) {
                    case "x" -> new float[]{angle, 0, 0};
                    case "y" -> new float[]{0, angle, 0};
                    case "z" -> new float[]{0, 0, angle};
                    default -> {
                        issues.add(sourceName + ": element rotation axis '" + axis + "' is unknown; skipped.");
                        yield null;
                    }
                };
            }
            if (rotation.isJsonArray() && rotation.getAsJsonArray().size() >= 3) {
                float rx = rotation.getAsJsonArray().get(0).getAsFloat();
                float ry = rotation.getAsJsonArray().get(1).getAsFloat();
                float rz = rotation.getAsJsonArray().get(2).getAsFloat();
                if (Math.abs(rx) < 1.0e-4f && Math.abs(ry) < 1.0e-4f && Math.abs(rz) < 1.0e-4f) {
                    return null;
                }
                int nonZero = (Math.abs(rx) > 1.0e-4f ? 1 : 0) + (Math.abs(ry) > 1.0e-4f ? 1 : 0)
                        + (Math.abs(rz) > 1.0e-4f ? 1 : 0);
                if (nonZero > 1) {
                    issues.add(sourceName + ": a multi-axis element rotation was found; only single-axis"
                            + " rotations are supported — rotate the group in Blockbench instead.");
                    return null;
                }
                return new float[]{rx, ry, rz};
            }
            return null;
        }

        // ------------------------------------------------------------------
        // Animations
        // ------------------------------------------------------------------

        private Map<String, ModelAnimation> parseAnimations(JsonArray animations, List<ModelBone> finishedBones) {
            Map<String, ModelAnimation> result = new LinkedHashMap<>();
            for (JsonElement entry : animations) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject animation = entry.getAsJsonObject();
                String rawName = string(animation, "name", "animation");
                ModelAnimation.Builder builder = ModelAnimation.builder(rawName);
                if (animation.has("length") && animation.get("length").isJsonPrimitive()) {
                    builder.lengthSeconds(animation.get("length").getAsFloat());
                }
                builder.loopMode(parseLoop(animation.get("loop")));

                if (animation.has("animators") && animation.get("animators").isJsonObject()) {
                    JsonObject animators = animation.getAsJsonObject("animators");
                    for (Map.Entry<String, JsonElement> animatorEntry : animators.entrySet()) {
                        if (!animatorEntry.getValue().isJsonObject()) {
                            continue;
                        }
                        Integer boneIndex = boneIndexByUuid.get(animatorEntry.getKey());
                        if (boneIndex == null || boneIndex >= finishedBones.size()) {
                            continue;
                        }
                        String boneName = finishedBones.get(boneIndex).name();
                        JsonObject animator = animatorEntry.getValue().getAsJsonObject();
                        parseChannel(animator.get("rotation"), boneName, "rotation", builder);
                        parseChannel(animator.get("position"), boneName, "position", builder);
                        parseChannel(animator.get("scale"), boneName, "scale", builder);
                    }
                }
                ModelAnimation parsed = builder.build();
                if (result.containsKey(parsed.name())) {
                    issues.add(sourceName + ": duplicate animation name '" + rawName + "'; the last wins.");
                }
                result.put(parsed.name(), parsed);
            }
            return result;
        }

        private static ModelAnimation.LoopMode parseLoop(JsonElement loop) {
            if (loop == null || loop.isJsonNull()) {
                return ModelAnimation.LoopMode.ONCE;
            }
            if (loop.isJsonPrimitive()) {
                if (loop.getAsJsonPrimitive().isBoolean()) {
                    return loop.getAsBoolean() ? ModelAnimation.LoopMode.LOOP : ModelAnimation.LoopMode.ONCE;
                }
                String value = loop.getAsString().toLowerCase(Locale.ROOT);
                return switch (value) {
                    case "loop", "true" -> ModelAnimation.LoopMode.LOOP;
                    case "hold_on_last_frame", "hold" -> ModelAnimation.LoopMode.HOLD;
                    default -> ModelAnimation.LoopMode.ONCE;
                };
            }
            return ModelAnimation.LoopMode.ONCE;
        }

        private void parseChannel(JsonElement channelElement, String boneName, String channel,
                                  ModelAnimation.Builder builder) {
            if (channelElement == null || !channelElement.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> keyEntry : channelElement.getAsJsonObject().entrySet()) {
                float time;
                try {
                    time = Float.parseFloat(keyEntry.getKey());
                } catch (NumberFormatException malformed) {
                    issues.add(sourceName + ": clip '" + boneName + "." + channel
                            + "' has a non-numeric key time '" + keyEntry.getKey() + "'; skipped.");
                    continue;
                }
                JsonElement value = keyEntry.getValue();
                ModelAnimation.Interpolation interpolation = ModelAnimation.Interpolation.LINEAR;
                float[] data = null;
                if (value.isJsonObject()) {
                    JsonObject key = value.getAsJsonObject();
                    data = floatArrayOr(key.get("data_point"), Float.NaN, 3);
                    String lerp = string(key, "lerp_mode", null);
                    if ("catmullrom".equalsIgnoreCase(lerp)) {
                        interpolation = ModelAnimation.Interpolation.CATMULLROM;
                    }
                } else if (value.isJsonArray()) {
                    data = floatArrayOr(value, Float.NaN, 3);
                } else if (value.isJsonPrimitive()) {
                    Double molang = Molang.numericConstant(value.getAsString());
                    data = molang == null ? new float[]{Float.NaN, Float.NaN, Float.NaN}
                            : new float[]{molang.floatValue(), molang.floatValue(), molang.floatValue()};
                }
                if (data == null) {
                    data = new float[]{0, 0, 0};
                }
                float x = floatOrZero(data, 0, boneName, channel);
                float y = floatOrZero(data, 1, boneName, channel);
                float z = floatOrZero(data, 2, boneName, channel);
                builder.key(boneName, channel, time, x, y, z, interpolation);
            }
        }

        private float floatOrZero(float[] data, int index, String boneName, String channel) {
            float value = data[index];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                if (molangWarned.put(boneName + "|" + channel, Boolean.TRUE) == null) {
                    issues.add(sourceName + ": clip '" + boneName + "." + channel
                            + "' contains Molang expressions; they sample as 0 (numeric keys still play).");
                }
                return 0.0f;
            }
            return value;
        }
    }

    // ------------------------------------------------------------------
    // Small JSON helpers
    // ------------------------------------------------------------------

    private static float[] floatArrayOr(JsonElement element, float fallback, int minSize) {
        float[] out = new float[Math.max(minSize, 3)];
        java.util.Arrays.fill(out, fallback);
        if (element == null || !element.isJsonArray()) {
            return out;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size() && i < out.length; i++) {
            JsonElement entry = array.get(i);
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isNumber()) {
                out[i] = entry.getAsFloat();
            } else if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                Double molang = Molang.numericConstant(entry.getAsString());
                if (molang != null) {
                    out[i] = molang.floatValue();
                }
            }
        }
        return out;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }
}
