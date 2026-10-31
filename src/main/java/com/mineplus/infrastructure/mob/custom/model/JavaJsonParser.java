package com.mineplus.infrastructure.mob.custom.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Java block/item model JSON (the {@code Blockbench → Java Block/Item}
 * export) into a single-bone {@link ModelData} for validation and static
 * display.
 *
 * <p>Java model UVs live in the {@code 0..16} texture-unit window (not pixels);
 * they are stored as parsed and only the raw file is ever shipped, so no
 * conversion can drift. A {@code parent} pointing at a vanilla builtin
 * ({@code block/cube_all}, {@code item/generated}, …) is accepted; a parent in
 * another namespace is reported as an unresolved reference, because those
 * files are separate pack assets this parser cannot see.</p>
 */
public final class JavaJsonParser {

    private JavaJsonParser() {
    }

    public static ModelData parse(String sourceName, byte[] bytes) {
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
        JsonObject root = rootElement.getAsJsonObject();
        List<String> issues = new ArrayList<>();

        String parent = root.has("parent") && root.get("parent").isJsonPrimitive()
                ? root.get("parent").getAsString() : null;
        if (parent != null && !parent.isEmpty()) {
            if (isVanillaParent(parent)) {
                issues.add(sourceName + ": parent '" + parent + "' is a vanilla builtin; only this file's"
                        + " own elements are validated and displayed.");
            } else {
                issues.add(sourceName + ": parent '" + parent + "' is another pack model and cannot be"
                        + " resolved here — validate it through its own file.");
            }
        }

        // Texture reference map ("#side" -> "ns:block/plank"), the resolved
        // targets become the texture list.
        Map<String, String> textureMap = new LinkedHashMap<>();
        if (root.has("textures") && root.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    textureMap.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        List<ModelTexture> textures = new ArrayList<>();
        Map<String, Integer> textureIndexByKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : textureMap.entrySet()) {
            String target = entry.getValue();
            if (target.startsWith("#")) {
                continue; // reference to another key; resolved entries land on their own line
            }
            textureIndexByKey.put(entry.getKey(), textures.size());
            textures.add(new ModelTexture(entry.getKey(), 16, 16, null, false));
        }

        List<ModelBone> bones = new ArrayList<>();
        bones.add(new ModelBone("root", "root", -1, 0, 0, 0, 0, 0, 0, List.of(), ModelBone.Attachment.NONE));
        Map<Integer, List<ModelCube>> cubesByBone = new LinkedHashMap<>();
        int rotationCounter = 0;
        if (root.has("elements") && root.get("elements").isJsonArray()) {
            for (JsonElement entry : root.getAsJsonArray("elements")) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject element = entry.getAsJsonObject();
                if (!element.has("from") || !element.has("to")
                        || !element.get("from").isJsonArray() || !element.get("to").isJsonArray()) {
                    issues.add(sourceName + ": an element has no from/to pair; skipped.");
                    continue;
                }
                JsonArray from = element.getAsJsonArray("from");
                JsonArray to = element.getAsJsonArray("to");
                if (from.size() < 3 || to.size() < 3) {
                    issues.add(sourceName + ": an element has a short from/to pair; skipped.");
                    continue;
                }
                float x1 = from.get(0).getAsFloat();
                float y1 = from.get(1).getAsFloat();
                float z1 = from.get(2).getAsFloat();
                float x2 = to.get(0).getAsFloat();
                float y2 = to.get(1).getAsFloat();
                float z2 = to.get(2).getAsFloat();
                if (x2 <= x1 || y2 <= y1 || z2 <= z1) {
                    issues.add(sourceName + ": an element has non-positive extents; skipped.");
                    continue;
                }
                Map<String, ModelCube.Uv> faces = new LinkedHashMap<>();
                if (element.has("faces") && element.get("faces").isJsonObject()) {
                    JsonObject faceObject = element.getAsJsonObject("faces");
                    for (String direction : new String[]{"north", "south", "east", "west", "up", "down"}) {
                        if (!faceObject.has(direction) || !faceObject.get(direction).isJsonObject()) {
                            continue;
                        }
                        JsonObject face = faceObject.getAsJsonObject(direction);
                        if (!face.has("uv") || !face.get("uv").isJsonArray() || face.getAsJsonArray("uv").size() < 4) {
                            continue;
                        }
                        JsonArray uv = face.getAsJsonArray("uv");
                        faces.put(direction, new ModelCube.Uv(
                                uv.get(0).getAsFloat(), uv.get(1).getAsFloat(),
                                uv.get(2).getAsFloat(), uv.get(3).getAsFloat()));
                    }
                }
                int textureIndex = -1;
                if (element.has("faces") && element.get("faces").isJsonObject()) {
                    JsonObject faceObject = element.getAsJsonObject("faces");
                    for (String direction : new String[]{"north", "south", "east", "west", "up", "down"}) {
                        if (!faceObject.has(direction) || !faceObject.get(direction).isJsonObject()) {
                            continue;
                        }
                        String reference = faceObject.getAsJsonObject(direction).has("texture")
                                && faceObject.getAsJsonObject(direction).get("texture").isJsonPrimitive()
                                ? faceObject.getAsJsonObject(direction).get("texture").getAsString() : null;
                        if (reference != null && reference.startsWith("#")) {
                            String resolved = textureMap.get(reference.substring(1));
                            if (resolved != null && textureIndexByKey.containsKey(resolved)) {
                                textureIndex = textureIndexByKey.get(resolved);
                            } else if (textureIndexByKey.containsKey(reference.substring(1))) {
                                textureIndex = textureIndexByKey.get(reference.substring(1));
                            }
                            if (textureIndex >= 0) {
                                break;
                            }
                        }
                    }
                }
                ModelCube cube = new ModelCube(x1, y1, z1, x2, y2, z2, 0.0f, textureIndex, faces);
                int ownerIndex = 0;
                float[] rotation = elementRotation(element, sourceName, issues);
                if (rotation != null) {
                    // Bake the element rotation into a synthetic sub-bone: the rig
                    // format has no element rotation and the client's is limited to
                    // one axis at ±45°, a bone rotation has neither limit.
                    float[] origin = new float[]{0, 0, 0};
                    if (element.has("rotation") && element.get("rotation").isJsonObject()
                            && element.getAsJsonObject("rotation").has("origin")
                            && element.getAsJsonObject("rotation").get("origin").isJsonArray()) {
                        JsonArray originArray = element.getAsJsonObject("rotation").getAsJsonArray("origin");
                        for (int i = 0; i < 3 && i < originArray.size(); i++) {
                            origin[i] = originArray.get(i).getAsFloat();
                        }
                    }
                    ownerIndex = bones.size();
                    bones.add(new ModelBone("root_rot" + (rotationCounter++), "element rotation",
                            0, origin[0], origin[1], origin[2],
                            rotation[0], rotation[1], rotation[2],
                            List.of(), ModelBone.Attachment.NONE));
                    cube = new ModelCube(x1 - origin[0], y1 - origin[1], z1 - origin[2],
                            x2 - origin[0], y2 - origin[1], z2 - origin[2],
                            cube.inflate(), textureIndex, faces);
                }
                cubesByBone.computeIfAbsent(ownerIndex, k -> new ArrayList<>()).add(cube);
            }
        } else {
            issues.add(sourceName + ": no elements; the model only re-parents or references another model.");
        }

        List<ModelBone> finished = new ArrayList<>(bones.size());
        for (int i = 0; i < bones.size(); i++) {
            ModelBone bone = bones.get(i);
            finished.add(new ModelBone(bone.name(), bone.rawName(), bone.parentIndex(),
                    bone.pivotX(), bone.pivotY(), bone.pivotZ(),
                    bone.restRotationX(), bone.restRotationY(), bone.restRotationZ(),
                    cubesByBone.getOrDefault(i, List.of()), bone.attachment()));
        }
        return new ModelData(sourceName, 16, 16, finished, textures, Map.of(), issues);
    }

    /** The element rotation as {rx, ry, rz} degrees, or {@code null} when none. */
    private static float[] elementRotation(JsonObject element, String sourceName, List<String> issues) {
        if (!element.has("rotation") || !element.get("rotation").isJsonObject()) {
            return null;
        }
        JsonObject rotation = element.getAsJsonObject("rotation");
        String axis = rotation.has("axis") && rotation.get("axis").isJsonPrimitive()
                ? rotation.get("axis").getAsString() : "";
        float angle = rotation.has("angle") && rotation.get("angle").isJsonPrimitive()
                ? rotation.get("angle").getAsFloat() : 0.0f;
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

    private static boolean isVanillaParent(String parent) {
        String normalized = parent.startsWith("minecraft:") ? parent.substring("minecraft:".length()) : parent;
        return normalized.startsWith("block/") || normalized.startsWith("item/")
                || normalized.startsWith("builtin/") || normalized.equals("block") || normalized.equals("item");
    }
}
