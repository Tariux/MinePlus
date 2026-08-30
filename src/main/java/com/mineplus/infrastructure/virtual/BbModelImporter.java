package com.mineplus.infrastructure.virtual;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.animation.Keyframe;
import com.mineplus.infrastructure.virtual.animation.KeyframeInterpolation;
import com.mineplus.infrastructure.virtual.animation.LoopMode;
import com.mineplus.infrastructure.virtual.animation.VirtualBone;
import com.mineplus.util.DebugLogger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <p>Animations are parsed in the same single pass: clip metadata, per-bone animators
 * (keyed by outliner group uuid, converted to bone names), and keyframes with
 * molang-tolerant value parsing (non-numeric expressions fall back to 0 and are reported).
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
        List<RawAnimation> rawAnimations = new ArrayList<>();

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
                case "animations" -> parseAnimations(json, rawAnimations);
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

        // Bone graph from the outliner groups: preorder (parents precede children)
        // so the animation evaluator can compose world deltas in one forward pass.
        List<VirtualBone> bones = new ArrayList<>();
        Map<String, Integer> boneIndexByUuid = new HashMap<>();
        Map<String, Integer> elementBoneIndex = new HashMap<>();
        for (RawGroup group : rootOutliner) {
            buildBones(group, -1, bones, boneIndexByUuid, elementBoneIndex, new HashSet<>());
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
                    element.lightEmission,
                    element.uuid == null ? -1 : elementBoneIndex.getOrDefault(element.uuid, -1)
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

        List<AnimationClip> animations = buildClips(rawAnimations, bones, boneIndexByUuid);

        return new VirtualModel(
                name,
                bakedCubes,
                textureLookup.toIdMap(),
                resolution,
                modelFormat,
                anchors,
                List.copyOf(bones),
                animations
        );
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
                        case "name" -> group.name = nextStringOrNull(json);
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

    private static void parseAnimations(JsonReader json, List<RawAnimation> output) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            RawAnimation animation = new RawAnimation();
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "name" -> animation.name = nextStringOrNull(json);
                    case "loop" -> animation.loop = nextStringOrNull(json);
                    case "length" -> animation.length = (float) nextDouble(json, 0.0);
                    case "animators" -> parseAnimators(json, animation);
                    default -> json.skipValue(); // uuid, override, snapping, markers, delays, ...
                }
            }
            json.endObject();
            output.add(animation);
        }
        json.endArray();
    }

    private static void parseAnimators(JsonReader json, RawAnimation animation) throws Exception {
        json.beginObject();
        while (json.hasNext()) {
            String boneKey = json.nextName();
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            RawAnimator animator = new RawAnimator();
            animator.uuid = boneKey;
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "name" -> animator.name = nextStringOrNull(json);
                    case "type" -> animator.type = nextStringOrNull(json);
                    case "keyframes" -> parseKeyframes(json, animator);
                    default -> json.skipValue(); // rotation_global, quaternion_interpolation, ...
                }
            }
            json.endObject();
            // Only bone animators drive geometry; effect animators (sounds,
            // particles, timelines) are dead branches for a display renderer.
            if (animator.type == null || "bone".equalsIgnoreCase(animator.type)) {
                animation.animators.put(boneKey, animator);
            }
        }
        json.endObject();
    }

    private static void parseKeyframes(JsonReader json, RawAnimator animator) throws Exception {
        json.beginArray();
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            RawKeyframe keyframe = new RawKeyframe();
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "channel" -> keyframe.channel = nextStringOrNull(json);
                    case "time" -> keyframe.time = (float) nextDouble(json, 0.0);
                    case "interpolation" -> keyframe.interpolation = nextStringOrNull(json);
                    case "data_points" -> parseDataPoints(json, keyframe);
                    default -> json.skipValue();
                }
            }
            json.endObject();
            animator.keyframes.add(keyframe);
        }
        json.endArray();
    }

    /** Reads the first data point's x/y/z; the remaining points are skipped. */
    private static void parseDataPoints(JsonReader json, RawKeyframe keyframe) throws Exception {
        json.beginArray();
        boolean first = true;
        while (json.hasNext()) {
            if (!first || json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                continue;
            }
            first = false;
            json.beginObject();
            while (json.hasNext()) {
                String fieldName = json.nextName();
                switch (fieldName) {
                    case "x" -> keyframe.x = nextMolangFloat(json, 0.0f);
                    case "y" -> keyframe.y = nextMolangFloat(json, 0.0f);
                    case "z" -> keyframe.z = nextMolangFloat(json, 0.0f);
                    default -> json.skipValue();
                }
            }
            json.endObject();
        }
        json.endArray();
    }

    /**
     * Molang-tolerant numeric read: numbers and numeric strings parse; molang
     * expressions (e.g. {@code math.sin(q.anim_time * 90)}) fall back to the
     * given default — evaluating them needs an animation context the importer
     * does not have.
     */
    private static float nextMolangFloat(JsonReader json, float fallback) throws Exception {
        JsonToken peek = json.peek();
        if (peek == JsonToken.NUMBER) {
            return (float) json.nextDouble();
        }
        if (peek == JsonToken.STRING) {
            String value = json.nextString();
            try {
                return Float.parseFloat(value.trim());
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

    /**
     * Builds the preorder bone list. Also records, for every element uuid, the
     * index of its innermost containing group — the cube's animating bone.
     */
    private static int buildBones(
            RawGroup group,
            int parentIndex,
            List<VirtualBone> bones,
            Map<String, Integer> boneIndexByUuid,
            Map<String, Integer> elementBoneIndex,
            Set<String> visited
    ) {
        if (group.uuid != null && !group.uuid.isBlank()) {
            if (!visited.add(group.uuid)) {
                return -1;
            }
        }
        int index = bones.size();
        bones.add(null);
        if (group.uuid != null && !group.uuid.isBlank()) {
            boneIndexByUuid.put(group.uuid, index);
        }
        for (String childUuid : group.childElementUuids) {
            if (childUuid != null && !childUuid.isBlank()) {
                elementBoneIndex.put(childUuid, index);
            }
        }

        List<Integer> childIndices = new ArrayList<>();
        for (RawGroup child : group.children) {
            int childIndex = buildBones(child, index, bones, boneIndexByUuid, elementBoneIndex, visited);
            if (childIndex >= 0) {
                childIndices.add(childIndex);
            }
        }

        String name = group.name != null && !group.name.isBlank() ? group.name : "bone_" + index;
        Vector3f pivot = group.origin == null ? new Vector3f() : new Vector3f(group.origin);
        bones.set(index, new VirtualBone(name, group.uuid, parentIndex, pivot, childIndices));
        return index;
    }

    /** Converts raw animation captures into clips keyed by bone name. */
    private static List<AnimationClip> buildClips(
            List<RawAnimation> rawAnimations,
            List<VirtualBone> bones,
            Map<String, Integer> boneIndexByUuid
    ) {
        List<AnimationClip> clips = new ArrayList<>();
        for (RawAnimation raw : rawAnimations) {
            Map<String, AnimationClip.BoneAnimation> animators = new LinkedHashMap<>();
            float maxTime = 0.0f;
            for (RawAnimator animator : raw.animators.values()) {
                List<Keyframe> rotation = new ArrayList<>();
                List<Keyframe> position = new ArrayList<>();
                List<Keyframe> scale = new ArrayList<>();
                for (RawKeyframe kf : animator.keyframes) {
                    Keyframe converted = new Keyframe(
                            kf.time, kf.x, kf.y, kf.z,
                            KeyframeInterpolation.fromKey(kf.interpolation, KeyframeInterpolation.LINEAR)
                    );
                    String channel = kf.channel == null ? "" : kf.channel.trim().toLowerCase(Locale.ROOT);
                    switch (channel) {
                        case "rotation" -> rotation.add(converted);
                        case "position" -> position.add(converted);
                        case "scale" -> scale.add(converted);
                        default -> {
                        }
                    }
                    maxTime = Math.max(maxTime, kf.time);
                }
                if (rotation.isEmpty() && position.isEmpty() && scale.isEmpty()) {
                    continue;
                }

                String boneName = null;
                Integer boneIndex = animator.uuid == null ? null : boneIndexByUuid.get(animator.uuid);
                if (boneIndex != null && boneIndex < bones.size()) {
                    boneName = bones.get(boneIndex).name();
                } else if (animator.name != null && !animator.name.isBlank()) {
                    boneName = animator.name;
                }
                if (boneName == null || boneName.isBlank()) {
                    DebugLogger.warning("bbmodel import: dropping animator without resolvable bone (uuid="
                            + animator.uuid + ") in animation '" + raw.name + "'.");
                    continue;
                }
                if (animators.put(boneName, new AnimationClip.BoneAnimation(rotation, position, scale)) != null) {
                    DebugLogger.warning("bbmodel import: duplicate animator bone name '" + boneName
                            + "' in animation '" + raw.name + "'; keeping the last one.");
                }
            }
            if (animators.isEmpty()) {
                continue;
            }
            float length = raw.length > 0.0f ? raw.length : maxTime;
            clips.add(new AnimationClip(
                    raw.name,
                    LoopMode.fromKey(raw.loop, LoopMode.ONCE),
                    length,
                    animators
            ));
        }
        return clips;
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
        // Blockbench writes "path": null when textures are linked from an external
        // library; the fallback chain (relative_path, name) then has to answer.
        if (rawValue == null) {
            return null;
        }
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
        String name;
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

    /** Streaming holder for one animation. */
    private static final class RawAnimation {
        String name;
        String loop;
        float length;
        final Map<String, RawAnimator> animators = new LinkedHashMap<>();
    }

    /** Streaming holder for one bone animator of an animation. */
    private static final class RawAnimator {
        String uuid;
        String name;
        String type;
        final List<RawKeyframe> keyframes = new ArrayList<>();
    }

    /** Streaming holder for one keyframe (first data point's channels). */
    private static final class RawKeyframe {
        String channel;
        String interpolation;
        float time;
        float x;
        float y;
        float z;
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
