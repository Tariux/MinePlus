package com.mineplus.infrastructure.virtual;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class BbModelImporter {

    public static VirtualModel parse(String name, File file) {
        return parse(name, file, null);
    }

    public static VirtualModel parse(String name, File file, Logger logger) {
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            return parse(name, reader, logger);
        } catch (Exception exception) {
            if (logger != null) {
                logger.warning("Failed to parse bbmodel '" + name + "' from " + file.getAbsolutePath()
                        + ": " + exception.getMessage());
            } else {
                exception.printStackTrace();
            }
            return null;
        }
    }

    public static VirtualModel parse(String name, Reader reader, Logger logger) {
        List<BakedCube> bakedCubes = new ArrayList<>();
        TextureLookup textureLookup = new TextureLookup();

        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray textures = root.has("textures") && root.get("textures").isJsonArray()
                    ? root.getAsJsonArray("textures")
                    : new JsonArray();
            textureLookup.parse(textures);

            JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                    ? root.getAsJsonArray("elements")
                    : new JsonArray();
            Map<String, JsonObject> elementsByUuid = new HashMap<>();
            for (JsonElement elem : elements) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject cube = elem.getAsJsonObject();
                if (cube.has("type") && !"cube".equalsIgnoreCase(cube.get("type").getAsString())) {
                    continue;
                }
                String uuid = getString(cube, "uuid");
                if (uuid != null && !uuid.isBlank()) {
                    elementsByUuid.put(uuid, cube);
                }
            }

            Map<String, JsonObject> groupObjectsByUuid = new HashMap<>();
            JsonArray outliner = root.has("outliner") && root.get("outliner").isJsonArray()
                    ? root.getAsJsonArray("outliner")
                    : new JsonArray();
            collectGroupObjects(outliner, groupObjectsByUuid);

            Map<String, Matrix4f> elementTransforms = new HashMap<>();
            Map<String, Boolean> elementEnabled = new HashMap<>();
            for (JsonElement entry : outliner) {
                applyOutlinerEntry(
                        entry,
                        new Matrix4f(),
                        true,
                        elementsByUuid,
                        groupObjectsByUuid,
                        elementTransforms,
                        elementEnabled,
                        new HashSet<>()
                );
            }

            for (JsonElement elem : elements) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject cube = elem.getAsJsonObject();
                if (cube.has("type") && !"cube".equalsIgnoreCase(cube.get("type").getAsString())) {
                    continue;
                }

                JsonArray fromArr = cube.getAsJsonArray("from");
                JsonArray toArr = cube.getAsJsonArray("to");
                if (fromArr == null || toArr == null || fromArr.size() < 3 || toArr.size() < 3) {
                    continue;
                }

                String cubeUuid = getString(cube, "uuid");
                if (cubeUuid != null && Boolean.FALSE.equals(elementEnabled.get(cubeUuid))) {
                    continue;
                }
                if (cube.has("export") && !cube.get("export").getAsBoolean()) {
                    continue;
                }

                float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0f;
                Vector3f from = parseVector(fromArr).sub(inflate, inflate, inflate);
                Vector3f to = parseVector(toArr).add(inflate, inflate, inflate);
                Vector3f rawScale = new Vector3f(
                        (to.x - from.x) / 16.0f,
                        (to.y - from.y) / 16.0f,
                        (to.z - from.z) / 16.0f
                );

                Matrix4f cubeMatrix = new Matrix4f()
                        .translate(from.x / 16.0f, from.y / 16.0f, from.z / 16.0f)
                        .scale(rawScale);
                cubeMatrix = parsePivotRotation(cube).mul(cubeMatrix, new Matrix4f());

                Matrix4f parentMatrix = cubeUuid == null
                        ? new Matrix4f()
                        : new Matrix4f(elementTransforms.getOrDefault(cubeUuid, new Matrix4f()));
                Matrix4f finalMatrix = parentMatrix.mul(cubeMatrix, new Matrix4f());

                Vector3f translation = finalMatrix.getTranslation(new Vector3f());
                Vector3f bakedScale = finalMatrix.getScale(new Vector3f());
                Quaternionf bakedRotation = finalMatrix.getUnnormalizedRotation(new Quaternionf());

                JsonObject facesNode = cube.has("faces") ? cube.getAsJsonObject("faces") : null;
                EnumMap<CubeFace, BakedFace> faces = parseFaces(facesNode, textureLookup);
                String cubeName = getString(cube, "name");
                String primaryTexture = choosePrimaryTexture(faces);

                bakedCubes.add(new BakedCube(
                        cubeName,
                        translation,
                        bakedRotation,
                        bakedScale,
                        new Quaternionf(),
                        faces,
                        primaryTexture
                ));
            }
        } catch (Exception exception) {
            if (logger != null) {
                logger.warning("Failed to parse bbmodel '" + name + "': " + exception.getMessage());
            } else {
                exception.printStackTrace();
            }
            return null;
        }

        return new VirtualModel(name, bakedCubes, textureLookup.toIdMap());
    }

    private static void collectGroupObjects(JsonArray outliner, Map<String, JsonObject> groups) {
        if (outliner == null) {
            return;
        }
        for (JsonElement entry : outliner) {
            if (entry.isJsonObject()) {
                JsonObject group = entry.getAsJsonObject();
                String uuid = getString(group, "uuid");
                if (uuid != null && !uuid.isBlank()) {
                    groups.put(uuid, group);
                }
                if (group.has("children") && group.get("children").isJsonArray()) {
                    collectGroupObjects(group.getAsJsonArray("children"), groups);
                }
            }
        }
    }

    private static void applyOutlinerEntry(
            JsonElement entry,
            Matrix4f parentTransform,
            boolean parentVisible,
            Map<String, JsonObject> elementsByUuid,
            Map<String, JsonObject> groupObjectsByUuid,
            Map<String, Matrix4f> elementTransforms,
            Map<String, Boolean> elementEnabled,
            Set<String> visited
    ) {
        if (entry.isJsonPrimitive()) {
            String uuid = entry.getAsString();
            if (uuid != null && !uuid.isBlank() && elementsByUuid.containsKey(uuid)) {
                elementTransforms.put(uuid, new Matrix4f(parentTransform));
                elementEnabled.put(uuid, parentVisible);
            }
            return;
        }

        if (!entry.isJsonObject()) {
            return;
        }

        JsonObject group = entry.getAsJsonObject();
        String groupUuid = getString(group, "uuid");
        if (groupUuid != null && !visited.add(groupUuid)) {
            return;
        }

        boolean visible = parentVisible && (!group.has("visibility") || group.get("visibility").getAsBoolean());
        Matrix4f groupTransform = parentTransform.mul(parsePivotRotation(group), new Matrix4f());

        JsonArray children = group.has("children") && group.get("children").isJsonArray()
                ? group.getAsJsonArray("children")
                : new JsonArray();
        for (JsonElement child : children) {
            applyOutlinerEntry(
                    child,
                    groupTransform,
                    visible,
                    elementsByUuid,
                    groupObjectsByUuid,
                    elementTransforms,
                    elementEnabled,
                    visited
            );
        }
    }

    private static Matrix4f parsePivotRotation(JsonObject node) {
        JsonArray originArr = node.has("origin") ? node.getAsJsonArray("origin") : null;
        JsonArray rotationArr = node.has("rotation") ? node.getAsJsonArray("rotation") : null;
        if (rotationArr == null || originArr == null) {
            return new Matrix4f();
        }

        Vector3f origin = parseVector(originArr);
        Vector3f rotation = parseVector(rotationArr);
        if (rotation.x == 0 && rotation.y == 0 && rotation.z == 0) {
            return new Matrix4f();
        }

        Quaternionf quaternion = new Quaternionf().rotateXYZ(
                (float) Math.toRadians(rotation.x),
                (float) Math.toRadians(rotation.y),
                (float) Math.toRadians(rotation.z)
        );

        return new Matrix4f()
                .translate(origin.x / 16.0f, origin.y / 16.0f, origin.z / 16.0f)
                .rotate(quaternion)
                .translate(-origin.x / 16.0f, -origin.y / 16.0f, -origin.z / 16.0f);
    }

    private static Vector3f parseVector(JsonArray array) {
        if (array == null || array.size() < 3) {
            return new Vector3f();
        }
        return new Vector3f(
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()
        );
    }

    private static EnumMap<CubeFace, BakedFace> parseFaces(JsonObject facesNode, TextureLookup lookup) {
        EnumMap<CubeFace, BakedFace> faces = new EnumMap<>(CubeFace.class);
        if (facesNode == null) {
            return faces;
        }

        for (Map.Entry<String, JsonElement> entry : facesNode.entrySet()) {
            CubeFace face = CubeFace.fromKey(entry.getKey());
            if (face == null || !entry.getValue().isJsonObject()) {
                continue;
            }

            JsonObject faceNode = entry.getValue().getAsJsonObject();
            float[] uv = parseUv(faceNode.getAsJsonArray("uv"));
            int rotation = faceNode.has("rotation") ? faceNode.get("rotation").getAsInt() : 0;
            String textureReference = null;
            String textureName = null;

            if (faceNode.has("texture") && !faceNode.get("texture").isJsonNull()) {
                JsonElement texElement = faceNode.get("texture");
                if (texElement.isJsonPrimitive() && texElement.getAsJsonPrimitive().isNumber()) {
                    int arrayIndex = texElement.getAsInt();
                    textureReference = String.valueOf(arrayIndex);
                    textureName = lookup.resolveByIndex(arrayIndex);
                } else if (texElement.isJsonPrimitive() && texElement.getAsJsonPrimitive().isString()) {
                    textureReference = texElement.getAsString();
                    textureName = lookup.resolveByReference(textureReference);
                }
            }

            faces.put(face, new BakedFace(
                    uv[0], uv[1], uv[2], uv[3], rotation, textureReference, textureName
            ));
        }
        return faces;
    }

    private static float[] parseUv(JsonArray uvArray) {
        if (uvArray == null || uvArray.size() < 4) {
            return new float[]{0f, 0f, 16f, 16f};
        }
        return new float[]{
                uvArray.get(0).getAsFloat(),
                uvArray.get(1).getAsFloat(),
                uvArray.get(2).getAsFloat(),
                uvArray.get(3).getAsFloat()
        };
    }

    private static String choosePrimaryTexture(Map<CubeFace, BakedFace> faces) {
        List<CubeFace> order = List.of(
                CubeFace.NORTH,
                CubeFace.SOUTH,
                CubeFace.EAST,
                CubeFace.WEST,
                CubeFace.UP,
                CubeFace.DOWN
        );
        for (CubeFace face : order) {
            BakedFace data = faces.get(face);
            if (data != null && data.textureName() != null && !data.textureName().isBlank()) {
                return data.textureName();
            }
        }
        return null;
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    static final class TextureLookup {

        private final Map<String, String> byId = new HashMap<>();
        private final Map<Integer, String> byIndex = new HashMap<>();

        void parse(JsonArray textures) {
            int arrayIndex = 0;
            for (JsonElement textureEntry : textures) {
                if (!textureEntry.isJsonObject()) {
                    arrayIndex++;
                    continue;
                }
                JsonObject texture = textureEntry.getAsJsonObject();
                String id = getString(texture, "id");
                String extracted = extractTextureIdentifier(texture);

                if (extracted != null && !extracted.isBlank()) {
                    byIndex.put(arrayIndex, extracted);
                    if (id != null && !id.isBlank()) {
                        byId.put(id, extracted);
                        byId.put("#" + id, extracted);
                    }
                }
                arrayIndex++;
            }
        }

        String resolveByIndex(int index) {
            return byIndex.get(index);
        }

        String resolveByReference(String reference) {
            if (reference == null || reference.isBlank()) {
                return null;
            }
            String direct = byId.get(reference);
            if (direct != null) {
                return direct;
            }
            if (reference.startsWith("#")) {
                return byId.get(reference.substring(1));
            }
            return byId.get("#" + reference);
        }

        Map<String, String> toIdMap() {
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, String> entry : byId.entrySet()) {
                if (!entry.getKey().startsWith("#")) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }

        private static String extractTextureIdentifier(JsonObject texture) {
            String[] keys = {"path", "relative_path", "name"};
            for (String key : keys) {
                String value = getString(texture, key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.replace('\\', '/').trim();
                if (normalized.isEmpty()) {
                    continue;
                }

                int slashIndex = normalized.lastIndexOf('/');
                String fileName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
                if (fileName.endsWith(".png")) {
                    fileName = fileName.substring(0, fileName.length() - 4);
                }
                if (fileName.endsWith(".mcmeta")) {
                    fileName = fileName.substring(0, fileName.length() - 7);
                }

                if (!fileName.isBlank()) {
                    return fileName.toLowerCase(Locale.ROOT);
                }
            }
            return null;
        }
    }
}
