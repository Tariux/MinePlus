package com.mineplus.infrastructure.virtual;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mineplus.util.DebugLogger;
import java.io.BufferedReader;
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

/**
 * Streaming importer for Blockbench {@code .bbmodel} files.
 *
 * <p>Uses a single-pass {@link JsonReader} state machine instead of a Gson DOM so that
 * dead branches — most importantly every texture's Base64 {@code source} PNG (KB–MB each) —
 * are skipped with {@link JsonReader#skipValue()} and never materialized as strings.
 *
 * <p>The outliner matrix accumulation (pivot conjugation {@code T(origin/16)·R·T(-origin/16)}
 * chained through parents, cycle-safe) is preserved verbatim from the DOM importer; only the
 * input construction changed.
 */
public class BbModelImporter {

    public static VirtualModel parse(String name, File file) {
        return parse(name, file, null);
    }

    public static VirtualModel parse(String name, File file, Logger logger) {
        if (!file.exists()) {
            return null;
        }

        try (Reader fileReader = new BufferedReader(new FileReader(file))) {
            return parse(name, fileReader, logger);
        } catch (Exception exception) {
            if (logger != null) {
                logger.warning("Failed to parse bbmodel '" + name + "' from " + file.getAbsolutePath()
                        + ": " + exception.getMessage());
            } else {
                DebugLogger.severe("Failed to parse bbmodel '" + name + "' from " + file.getAbsolutePath(), exception);
            }
            return null;
        }
    }

    public static VirtualModel parse(String name, Reader reader, Logger logger) {
        try {
            return parseStreamed(name, reader);
        } catch (Exception exception) {
            if (logger != null) {
                logger.warning("Failed to parse bbmodel '" + name + "': " + exception.getMessage());
            } else {
                DebugLogger.severe("Failed to parse bbmodel '" + name + "': " + exception.getMessage(), exception);
            }
            return null;
        }
    }

    private static VirtualModel parseStreamed(String name, Reader reader) throws Exception {
        List<BakedCube> bakedCubes = new ArrayList<>();
        TextureLookup textureLookup = new TextureLookup();
        VirtualModel.Resolution resolution = null;
        String modelFormat = null;

        // Raw element capture (kept as lightweight value holders, not Gson DOM).
        List<RawElement> rawElements = new ArrayList<>();
        List<RawAnchor> rawAnchors = new ArrayList<>();
        List<RawGroup> rootOutliner = new ArrayList<>();
        List<String> rootElementUuids = new ArrayList<>();

        JsonReader json = new JsonReader(reader);
        json.setLenient(false);
        json.beginObject();
        while (json.hasNext()) {
            String fieldName = json.nextName();
            switch (fieldName) {
                case "textures" -> parseTextures(json, textureLookup);
                case "elements" -> parseElements(json, rawElements, rawAnchors);
                case "outliner" -> parseOutliner(json, null, rootOutliner, rootElementUuids);
                case "resolution" -> resolution = parseResolution(json);
                case "meta" -> modelFormat = parseMeta(json);
                default -> json.skipValue();
            }
        }
        json.endObject();

        // Bake outliner transforms exactly like the DOM importer did.
        Map<String, Matrix4f> elementTransforms = new HashMap<>();
        Map<String, Boolean> elementEnabled = new HashMap<>();
        Set<String> knownElementUuids = new HashSet<>();
        for (RawElement element : rawElements) {
            if (element.uuid != null && !element.uuid.isBlank()) {
                knownElementUuids.add(element.uuid);
            }
        }
        // Root-level element UUIDs (cubes not inside any group): identity transform, visible.
        for (String rootUuid : rootElementUuids) {
            if (rootUuid != null && knownElementUuids.contains(rootUuid)) {
                elementTransforms.put(rootUuid, new Matrix4f());
                elementEnabled.put(rootUuid, true);
            }
        }
        for (RawGroup group : rootOutliner) {
            applyOutlinerEntry(
                    group,
                    new Matrix4f(),
                    true,
                    knownElementUuids,
                    elementTransforms,
                    elementEnabled,
                    new HashSet<>()
            );
        }

        for (RawElement element : rawElements) {
            if (!"cube".equalsIgnoreCase(element.type)) {
                continue;
            }
            if (element.from == null || element.to == null) {
                continue;
            }
            if (element.uuid != null && Boolean.FALSE.equals(elementEnabled.get(element.uuid))) {
                continue;
            }
            if (!element.export) {
                continue;
            }

            Vector3f from = new Vector3f(element.from).sub(element.inflate, element.inflate, element.inflate);
            Vector3f to = new Vector3f(element.to).add(element.inflate, element.inflate, element.inflate);
            Vector3f rawScale = new Vector3f(
                    (to.x - from.x) / 16.0f,
                    (to.y - from.y) / 16.0f,
                    (to.z - from.z) / 16.0f
            );

            Matrix4f cubeMatrix = new Matrix4f()
                    .translate(from.x / 16.0f, from.y / 16.0f, from.z / 16.0f)
                    .scale(rawScale);
            Matrix4f pivotMatrix = pivotRotation(element.origin, element.rotation);
            cubeMatrix = pivotMatrix.mul(cubeMatrix, new Matrix4f());

            Matrix4f parentMatrix = element.uuid == null
                    ? new Matrix4f()
                    : new Matrix4f(elementTransforms.getOrDefault(element.uuid, new Matrix4f()));
            Matrix4f finalMatrix = parentMatrix.mul(cubeMatrix, new Matrix4f());

            Vector3f translation = finalMatrix.getTranslation(new Vector3f());
            Vector3f bakedScale = finalMatrix.getScale(new Vector3f());
            Quaternionf bakedRotation = finalMatrix.getUnnormalizedRotation(new Quaternionf());

            EnumMap<CubeFace, BakedFace> faces = new EnumMap<>(CubeFace.class);
            for (Map.Entry<CubeFace, RawFace> entry : element.faces.entrySet()) {
                RawFace raw = entry.getValue();
                faces.put(entry.getKey(), new BakedFace(
                        raw.uv[0], raw.uv[1], raw.uv[2], raw.uv[3],
                        raw.rotation, raw.textureReference,
                        textureLookup.resolveByReference(raw.textureReference)
                ));
            }
            String primaryTexture = choosePrimaryTexture(faces);

            bakedCubes.add(new BakedCube(
                    element.name,
                    translation,
                    bakedRotation,
                    bakedScale,
                    new Quaternionf(),
                    faces,
                    primaryTexture,
                    element.lightEmission
            ));
        }

        List<VectorAnchor> anchors = new ArrayList<>();
        for (RawAnchor anchor : rawAnchors) {
            Matrix4f parent = anchor.uuid != null
                    ? new Matrix4f(elementTransforms.getOrDefault(anchor.uuid, new Matrix4f()))
                    : new Matrix4f();
            Matrix4f local = pivotRotation(anchor.origin, anchor.rotation);
            Matrix4f combined = parent.mul(local, new Matrix4f());
            anchors.add(new VectorAnchor(
                    anchor.name,
                    combined.getTranslation(new Vector3f()),
                    combined.getUnnormalizedRotation(new Quaternionf())
            ));
        }

        return new VirtualModel(name, bakedCubes, textureLookup.toIdMap(), resolution, modelFormat, anchors);
    }

    private static void parseTextures(JsonReader json, TextureLookup lookup) throws Exception {
        lookup.beginParse();
        json.beginArray();
        int arrayIndex = 0;
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                arrayIndex++;
                continue;
            }
            json.beginObject();
            String id = null;
            String path = null;
            String relativePath = null;
            String name = null;
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "id" -> id = nextStringOrNull(json);
                    case "path" -> path = nextStringOrNull(json);
                    case "relative_path" -> relativePath = nextStringOrNull(json);
                    case "name" -> name = nextStringOrNull(json);
                    default -> json.skipValue(); // "source" (Base64 PNG), "uuid", "particle", ...
                }
            }
            json.endObject();

            // Priority order matches the original importer: path, then relative_path, then name.
            String extracted = extractTextureIdentifier(path);
            if (extracted == null) {
                extracted = extractTextureIdentifier(relativePath);
            }
            if (extracted == null) {
                extracted = extractTextureIdentifier(name);
            }
            lookup.addTexture(arrayIndex, id, extracted);
            arrayIndex++;
        }
        json.endArray();
    }

    private static void parseElements(JsonReader json, List<RawElement> output, List<RawAnchor> anchors) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            RawElement element = new RawElement();
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "uuid" -> element.uuid = nextStringOrNull(json);
                    case "name" -> element.name = nextStringOrNull(json);
                    case "type" -> element.type = nextStringOrNull(json);
                    case "from" -> element.from = nextVector3(json);
                    case "to" -> element.to = nextVector3(json);
                    case "origin" -> element.origin = nextVector3(json);
                    case "rotation" -> element.rotation = nextVector3(json);
                    case "inflate" -> element.inflate = (float) nextDouble(json, 0.0);
                    case "export" -> element.export = nextBoolean(json, true);
                    case "light_emission" -> element.lightEmission = (int) nextDouble(json, 0.0);
                    case "faces" -> parseFaces(json, element.faces);
                    default -> json.skipValue(); // rescale, locked, color, autouv, render_order, ...
                }
            }
            json.endObject();
            output.add(element);
            if ("locator".equalsIgnoreCase(element.type) || "null_object".equalsIgnoreCase(element.type)) {
                RawAnchor anchor = new RawAnchor();
                anchor.uuid = element.uuid;
                anchor.name = element.name != null && !element.name.isBlank() ? element.name : element.uuid;
                anchor.origin = element.origin;
                anchor.rotation = element.rotation;
                anchors.add(anchor);
            }
        }
        json.endArray();
    }

    private static void parseFaces(JsonReader json, Map<CubeFace, RawFace> faces) throws Exception {
        json.beginObject();
        while (json.hasNext()) {
            String faceKey = json.nextName();
            CubeFace face = CubeFace.fromKey(faceKey);
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            RawFace raw = new RawFace();
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "uv" -> raw.uv = nextUv(json);
                    case "rotation" -> raw.rotation = (int) nextDouble(json, 0.0);
                    case "texture" -> raw.textureReference = nextTextureReference(json);
                    default -> json.skipValue();
                }
            }
            json.endObject();
            if (face != null) {
                faces.put(face, raw);
            }
        }
        json.endObject();
    }

    private static void parseOutliner(JsonReader json, RawGroup parent, List<RawGroup> siblings, List<String> rootElementUuids) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            JsonToken peek = json.peek();
            if (peek == JsonToken.STRING) {
                String uuid = json.nextString();
                if (parent != null) {
                    parent.childElementUuids.add(uuid);
                } else if (rootElementUuids != null) {
                    rootElementUuids.add(uuid);
                }
            } else if (peek == JsonToken.BEGIN_OBJECT) {
                RawGroup group = new RawGroup();
                json.beginObject();
                while (json.hasNext()) {
                    String fieldName = json.nextName();
                    switch (fieldName) {
                        case "uuid" -> group.uuid = nextStringOrNull(json);
                        case "origin" -> group.origin = nextVector3(json);
                        case "rotation" -> group.rotation = nextVector3(json);
                        case "visibility" -> group.visibility = nextBoolean(json, true);
                        case "children" -> parseOutliner(json, group, group.children, null);
                        default -> json.skipValue();
                    }
                }
                json.endObject();
                siblings.add(group);
            } else {
                json.skipValue();
            }
        }
        json.endArray();
    }

    private static VirtualModel.Resolution parseResolution(JsonReader json) throws Exception {
        int width = 16;
        int height = 16;
        json.beginObject();
        while (json.hasNext()) {
            String fieldName = json.nextName();
            switch (fieldName) {
                case "width" -> width = (int) nextDouble(json, 16.0);
                case "height" -> height = (int) nextDouble(json, 16.0);
                default -> json.skipValue();
            }
        }
        json.endObject();
        return new VirtualModel.Resolution(width, height);
    }

    /** Reads only {@code model_format}; skips the rest of the meta object. */
    private static String parseMeta(JsonReader json) throws Exception {
        String modelFormat = null;
        json.beginObject();
        while (json.hasNext()) {
            String fieldName = json.nextName();
            if ("model_format".equals(fieldName)) {
                modelFormat = nextStringOrNull(json);
            } else {
                json.skipValue();
            }
        }
        json.endObject();
        return modelFormat;
    }

    private static void applyOutlinerEntry(
            RawGroup group,
            Matrix4f parentTransform,
            boolean parentVisible,
            Set<String> knownElementUuids,
            Map<String, Matrix4f> elementTransforms,
            Map<String, Boolean> elementEnabled,
            Set<String> visited
    ) {
        if (group.uuid != null && !visited.add(group.uuid)) {
            return;
        }

        boolean visible = parentVisible && group.visibility;
        Matrix4f groupTransform = parentTransform.mul(
                pivotRotation(group.origin, group.rotation), new Matrix4f());

        for (String childUuid : group.childElementUuids) {
            if (childUuid == null || childUuid.isBlank()) {
                continue;
            }
            if (knownElementUuids.contains(childUuid)) {
                elementTransforms.put(childUuid, new Matrix4f(groupTransform));
                elementEnabled.put(childUuid, visible);
            }
        }

        for (RawGroup child : group.children) {
            applyOutlinerEntry(
                    child,
                    groupTransform,
                    visible,
                    knownElementUuids,
                    elementTransforms,
                    elementEnabled,
                    visited
            );
        }
    }

    private static Matrix4f pivotRotation(Vector3f origin, Vector3f rotation) {
        if (rotation == null || origin == null) {
            return new Matrix4f();
        }
        if (rotation.x == 0 && rotation.y == 0 && rotation.z == 0) {
            return new Matrix4f();
        }

        // Blockbench/vanilla Euler order: X applied first, then Y, then Z (extrinsic),
        // giving the compound Rz·Ry·Rx. Matches the reference Blockbench Import Library
        // (createQuaternion: rotateZ(z).rotateY(y).rotateX(x)) and the vanilla model
        // spec's X-then-Y-then-Z application order. JOML's rotateXYZ composes the
        // opposite way (Z first), which inverts multi-axis compound rotations.
        Quaternionf quaternion = new Quaternionf()
                .rotateZ((float) Math.toRadians(rotation.z))
                .rotateY((float) Math.toRadians(rotation.y))
                .rotateX((float) Math.toRadians(rotation.x));

        return new Matrix4f()
                .translate(origin.x / 16.0f, origin.y / 16.0f, origin.z / 16.0f)
                .rotate(quaternion)
                .translate(-origin.x / 16.0f, -origin.y / 16.0f, -origin.z / 16.0f);
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

    private static String extractTextureIdentifier(String rawValue) {
        String normalized = rawValue.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return null;
        }

        int slashIndex = normalized.lastIndexOf('/');
        String fileName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        if (fileName.endsWith(".png")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        if (fileName.endsWith(".mcmeta")) {
            fileName = fileName.substring(0, fileName.length() - 7);
        }

        if (fileName.isBlank()) {
            return null;
        }
        return fileName.toLowerCase(Locale.ROOT);
    }

    private static String nextStringOrNull(JsonReader json) throws Exception {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        return json.nextString();
    }

    private static boolean nextBoolean(JsonReader json, boolean fallback) throws Exception {
        JsonToken peek = json.peek();
        if (peek == JsonToken.BOOLEAN) {
            return json.nextBoolean();
        }
        if (peek == JsonToken.NULL) {
            json.nextNull();
            return fallback;
        }
        json.skipValue();
        return fallback;
    }

    private static double nextDouble(JsonReader json, double fallback) throws Exception {
        JsonToken peek = json.peek();
        if (peek == JsonToken.NUMBER) {
            return json.nextDouble();
        }
        if (peek == JsonToken.STRING) {
            try {
                return Double.parseDouble(json.nextString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        if (peek == JsonToken.NULL) {
            json.nextNull();
            return fallback;
        }
        json.skipValue();
        return fallback;
    }

    private static Vector3f nextVector3(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return null;
        }
        float[] values = new float[3];
        json.beginArray();
        int index = 0;
        while (json.hasNext() && index < 3) {
            values[index++] = (float) json.nextDouble();
        }
        while (json.hasNext()) {
            json.skipValue();
        }
        json.endArray();
        return new Vector3f(values[0], values[1], values[2]);
    }

    private static float[] nextUv(JsonReader json) throws Exception {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return new float[]{0f, 0f, 16f, 16f};
        }
        float[] uv = new float[]{0f, 0f, 16f, 16f};
        json.beginArray();
        int index = 0;
        while (json.hasNext() && index < 4) {
            uv[index++] = (float) json.nextDouble();
        }
        while (json.hasNext()) {
            json.skipValue();
        }
        json.endArray();
        return uv;
    }

    private static String nextTextureReference(JsonReader json) throws Exception {
        JsonToken peek = json.peek();
        if (peek == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        if (peek == JsonToken.NUMBER) {
            return String.valueOf((int) json.nextDouble());
        }
        if (peek == JsonToken.STRING) {
            return json.nextString();
        }
        json.skipValue();
        return null;
    }

    /** Streaming holder for one outliner group. */
    private static final class RawGroup {
        String uuid;
        Vector3f origin;
        Vector3f rotation;
        boolean visibility = true;
        final List<String> childElementUuids = new ArrayList<>();
        final List<RawGroup> children = new ArrayList<>();
    }

    /** Streaming holder for one element. */
    private static final class RawElement {
        String uuid;
        String name;
        String type = "cube";
        Vector3f from;
        Vector3f to;
        Vector3f origin;
        Vector3f rotation;
        float inflate = 0f;
        boolean export = true;
        int lightEmission = 0;
        final Map<CubeFace, RawFace> faces = new EnumMap<>(CubeFace.class);
    }

    /** Streaming holder for one element face. */
    private static final class RawFace {
        float[] uv = {0f, 0f, 16f, 16f};
        int rotation = 0;
        String textureReference;
    }

    /** Streaming holder for a {@code locator}/{@code null_object} anchor element. */
    private static final class RawAnchor {
        String uuid;
        String name;
        Vector3f origin;
        Vector3f rotation;
    }

    /**
     * Texture index/reference table built during streaming. Identifiers are deduplicated
     * so the same texture name repeating across faces/cubes shares one {@code String}.
     */
    static final class TextureLookup {

        private final Map<String, String> byId = new HashMap<>();
        private final Map<Integer, String> byIndex = new HashMap<>();
        private final Map<String, String> dedup = new HashMap<>();

        void beginParse() {
            byId.clear();
            byIndex.clear();
            dedup.clear();
        }

        void addTexture(int arrayIndex, String id, String extracted) {
            if (extracted == null || extracted.isBlank()) {
                return;
            }
            String canonical = dedup.computeIfAbsent(extracted, key -> key.intern());
            byIndex.put(arrayIndex, canonical);
            if (id != null && !id.isBlank()) {
                byId.put(id, canonical);
                byId.put("#" + id, canonical);
            }
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
                String stripped = reference.substring(1);
                direct = byId.get(stripped);
                if (direct != null) {
                    return direct;
                }
                reference = stripped;
            }
            // Numeric references are indices into the textures array.
            if (!reference.isEmpty()) {
                boolean digits = true;
                for (int i = 0; i < reference.length(); i++) {
                    char c = reference.charAt(i);
                    if (c < '0' || c > '9') {
                        digits = false;
                        break;
                    }
                }
                if (digits) {
                    try {
                        String byIdx = byIndex.get(Integer.parseInt(reference));
                        if (byIdx != null) {
                            return byIdx;
                        }
                    } catch (NumberFormatException ignored) {
                        // out of range int — fall through
                    }
                }
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
    }
}
