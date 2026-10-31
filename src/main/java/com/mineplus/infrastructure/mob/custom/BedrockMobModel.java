package com.mineplus.infrastructure.mob.custom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.util.DebugLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link CustomMobModel} from a Blockbench entity model
 * ({@code .bbmodel}, Bedrock format).
 *
 * <p>Two layouts are supported:</p>
 * <ul>
 *   <li>{@link Builder#part} — the caller declares which elements belong to
 *       which animatable part. A model file names no parts, so this is the
 *       explicit mapping; it is what a hand-authored showcase uses.</li>
 *   <li>{@link Builder#partsFromOutliner} — the file's own outliner becomes the
 *       part tree: every group is a part (its pivot is the group origin, its
 *       rest pose the group rotation, its geometry the elements directly inside
 *       it) and every child group is a child part. Nothing is merged or dropped,
 *       so the rig reproduces the authored hierarchy and an animation clip can
 *       drive individual bones.</li>
 * </ul>
 *
 * <p>Space mapping: a Bedrock element is authored in absolute pixels with
 * {@code y} up and the feet at {@code y = 0}; a {@link MobBox} is pivot-relative
 * with {@code y} down. Under a part pivot {@code (px, py, pz)} an element
 * {@code [from, to]} therefore becomes
 * {@code fromBox = (fromX - px, py - toY, fromZ - pz)} and
 * {@code toBox = (toX - px, py - fromY, toZ - pz)}. Per-face UV windows are
 * copied unchanged (they already follow the client's model-face convention),
 * {@code inflate} grows the box before that conversion, and a single-axis
 * element rotation keeps its authored angle with an origin expressed relative
 * to the part pivot.</p>
 *
 * <p>Animations are read in the same pass ({@link Builder#animationClips}):
 * every {@code bone} animator becomes a track of the part with the same name.
 * Keyframes are sampled linearly; molang expressions are not evaluated, so an
 * unparsable value becomes {@code 0} and the numeric keys still play.</p>
 */
public final class BedrockMobModel {

    /** Longest clip length accepted; guards a malformed {@code length}. */
    private static final float MAX_CLIP_SECONDS = 600.0f;

    private BedrockMobModel() {
    }

    /**
     * Parses a Blockbench model and starts a part layout over it.
     *
     * @throws IllegalArgumentException when the file is not a readable Blockbench model
     */
    public static Builder parse(byte[] bbmodelJson) {
        Objects.requireNonNull(bbmodelJson, "bbmodelJson");
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(bbmodelJson, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Model is not valid Blockbench JSON: " + malformed.getMessage(),
                    malformed);
        }
        int textureWidth = 64;
        int textureHeight = 64;
        JsonObject resolution = root.has("resolution") && root.get("resolution").isJsonObject()
                ? root.getAsJsonObject("resolution") : null;
        if (resolution != null) {
            textureWidth = resolution.has("width") ? resolution.get("width").getAsInt() : textureWidth;
            textureHeight = resolution.has("height") ? resolution.get("height").getAsInt() : textureHeight;
        }

        Map<String, Element> byName = new LinkedHashMap<>();
        Map<String, Element> byUuid = new LinkedHashMap<>();
        JsonArray array = root.has("elements") && root.get("elements").isJsonArray()
                ? root.getAsJsonArray("elements") : null;
        if (array != null) {
            for (JsonElement entry : array) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                Element element = Element.read(entry.getAsJsonObject());
                if (element != null) {
                    byName.put(element.name(), element);
                    if (element.uuid() != null) {
                        byUuid.put(element.uuid(), element);
                    }
                }
            }
        }
        if (byName.isEmpty()) {
            throw new IllegalArgumentException("Blockbench model has no usable elements");
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        JsonArray groupArray = root.has("groups") && root.get("groups").isJsonArray()
                ? root.getAsJsonArray("groups") : null;
        if (groupArray != null) {
            for (JsonElement entry : groupArray) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject group = entry.getAsJsonObject();
                String uuid = string(group, "uuid");
                if (uuid != null) {
                    groups.put(uuid, new Group(uuid, string(group, "name"),
                            vector(group, "origin", new float[]{0.0f, 0.0f, 0.0f}),
                            vector(group, "rotation", new float[]{0.0f, 0.0f, 0.0f})));
                }
            }
        }

        JsonArray outliner = root.has("outliner") && root.get("outliner").isJsonArray()
                ? root.getAsJsonArray("outliner") : null;
        JsonArray animations = root.has("animations") && root.get("animations").isJsonArray()
                ? root.getAsJsonArray("animations") : null;
        return new Builder(byName, byUuid, groups, outliner, animations, textureWidth, textureHeight);
    }

    /** One element as authored: absolute y-up pixels, explicit face windows. */
    private record Element(
            String name,
            String uuid,
            float fromX,
            float fromY,
            float fromZ,
            float toX,
            float toY,
            float toZ,
            float inflate,
            MobFaceUvs faceUvs,
            float rotationX,
            float rotationY,
            float rotationZ,
            float originX,
            float originY,
            float originZ,
            boolean rotatedFaces
    ) {

        private static final String[] DIRECTIONS = {"north", "south", "east", "west", "up", "down"};

        /** {@code null} when the element is unusable (no box, no faces). */
        static Element read(JsonObject json) {
            String name = json.has("name") && !json.get("name").isJsonNull()
                    ? json.get("name").getAsString() : null;
            if (name == null || name.isBlank()) {
                return null;
            }
            if (!json.has("from") || !json.has("to")
                    || !json.get("from").isJsonArray() || !json.get("to").isJsonArray()) {
                return null;
            }
            float[] from = readVector(json.getAsJsonArray("from"));
            float[] to = readVector(json.getAsJsonArray("to"));
            if (from == null || to == null
                    || to[0] <= from[0] || to[1] <= from[1] || to[2] <= from[2]) {
                return null;
            }
            float inflate = json.has("inflate") && !json.get("inflate").isJsonNull()
                    ? json.get("inflate").getAsFloat() : 0.0f;

            float[] rotation = vector(json, "rotation", new float[]{0.0f, 0.0f, 0.0f});
            float[] origin = vector(json, "origin", new float[]{0.0f, 0.0f, 0.0f});

            boolean rotatedFaces = false;
            MobFaceUvs.Uv[] windows = new MobFaceUvs.Uv[DIRECTIONS.length];
            JsonObject faces = json.has("faces") && json.get("faces").isJsonObject()
                    ? json.getAsJsonObject("faces") : null;
            if (faces != null) {
                for (int i = 0; i < DIRECTIONS.length; i++) {
                    JsonElement faceElement = faces.get(DIRECTIONS[i]);
                    if (faceElement == null || !faceElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject face = faceElement.getAsJsonObject();
                    if (face.has("uv") && face.get("uv").isJsonArray()) {
                        float[] uv = readVector(face.getAsJsonArray("uv"));
                        if (uv != null) {
                            windows[i] = new MobFaceUvs.Uv(uv[0], uv[1], uv[2], uv[3]);
                        }
                    }
                    if (face.has("rotation") && !face.get("rotation").isJsonNull()
                            && Math.abs(face.get("rotation").getAsFloat()) > 1.0e-3f) {
                        rotatedFaces = true;
                    }
                }
            }
            if (windows[0] == null && windows[1] == null && windows[2] == null
                    && windows[3] == null && windows[4] == null && windows[5] == null) {
                return null;
            }
            return new Element(name, string(json, "uuid"),
                    from[0], from[1], from[2], to[0], to[1], to[2], inflate,
                    new MobFaceUvs(windows[0], windows[1], windows[2], windows[3], windows[4], windows[5]),
                    rotation[0], rotation[1], rotation[2], origin[0], origin[1], origin[2],
                    rotatedFaces);
        }

        /** The single axis this element rotates about, or {@code 0} when it is axis-aligned. */
        char rotationAxis() {
            int axes = 0;
            char axis = 0;
            if (Math.abs(rotationX) > 1.0e-3f) {
                axes++;
                axis = 'x';
            }
            if (Math.abs(rotationY) > 1.0e-3f) {
                axes++;
                axis = 'y';
            }
            if (Math.abs(rotationZ) > 1.0e-3f) {
                axes++;
                axis = 'z';
            }
            return axes == 1 ? axis : (char) 0;
        }

        float rotationAngle() {
            return switch (rotationAxis()) {
                case 'x' -> rotationX;
                case 'y' -> rotationY;
                default -> rotationZ;
            };
        }

        private static float[] readVector(JsonArray array) {
            if (array == null || array.size() < 3) {
                return null;
            }
            float[] values = new float[4];
            for (int i = 0; i < Math.min(4, array.size()); i++) {
                values[i] = array.get(i).getAsFloat();
            }
            return values;
        }
    }

    /**
     * One outliner group: its name, its absolute (y-up) pivot and its authored
     * rest rotation in y-up degrees.
     */
    private record Group(String uuid, String name, float[] origin, float[] rotation) {
    }

    /** The part layout of one model. */
    public static final class Builder {

        private final Map<String, Element> elementsByName;
        private final Map<String, Element> elementsByUuid;
        private final Map<String, Group> groupsByUuid;
        private final JsonArray outliner;
        private final JsonArray animationJson;
        private final int textureWidth;
        private final int textureHeight;
        private final List<PartSpec> parts = new ArrayList<>();
        private final Map<String, MobPart.Role> roles = new LinkedHashMap<>();
        private boolean importOutliner;
        private int modelHeightPx;
        private String textureName = "texture";

        private Builder(Map<String, Element> elementsByName, Map<String, Element> elementsByUuid,
                        Map<String, Group> groupsByUuid, JsonArray outliner, JsonArray animationJson,
                        int textureWidth, int textureHeight) {
            this.elementsByName = elementsByName;
            this.elementsByUuid = elementsByUuid;
            this.groupsByUuid = groupsByUuid;
            this.outliner = outliner;
            this.animationJson = animationJson;
            this.textureWidth = Math.max(1, textureWidth);
            this.textureHeight = Math.max(1, textureHeight);
        }

        /**
         * Declares one animatable part.
         *
         * @param name         part name ({@code [a-z0-9_]}), also its asset name
         * @param role         how the rig animates it
         * @param pivotX       pivot in absolute model pixels (y up, feet at 0)
         * @param pivotY       pivot in absolute model pixels (y up, feet at 0)
         * @param pivotZ       pivot in absolute model pixels (y up, feet at 0)
         * @param elementNames Blockbench element names owned by this part
         */
        public Builder part(String name, MobPart.Role role,
                            float pivotX, float pivotY, float pivotZ, String... elementNames) {
            Objects.requireNonNull(elementNames, "elementNames");
            parts.add(new PartSpec(name, role == null ? MobPart.Role.STATIC : role,
                    pivotX, pivotY, pivotZ, 0.0f, 0.0f, 0.0f, -1, List.of(elementNames), List.of()));
            return this;
        }

        /**
         * Uses the file's outliner as the part tree: every group becomes a part
         * with its authored pivot, rest rotation and parent, and the elements
         * directly inside it are its geometry. Groups with no geometry stay as
         * bones that move their children.
         */
        public Builder partsFromOutliner() {
            this.importOutliner = true;
            return this;
        }

        /** Overrides the role of a part declared by {@link #partsFromOutliner()}. */
        public Builder role(String partName, MobPart.Role role) {
            if (partName != null && role != null) {
                roles.put(MobAnimation.normalizePart(partName), role);
            }
            return this;
        }

        /** Reads the file's animation clips ({@code walk}, {@code idle}, …). */
        public Builder animationClips() {
            return this;
        }

        /** Ground line in model pixels; defaults to the top of the referenced geometry. */
        public Builder modelHeightPx(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("modelHeightPx must be positive");
            }
            this.modelHeightPx = value;
            return this;
        }

        /** Texture file stem inside the pack (default {@code texture}). */
        public Builder textureName(String value) {
            this.textureName = value;
            return this;
        }

        public CustomMobModel build(byte[] texturePng) {
            List<PartSpec> layout = parts;
            if (importOutliner) {
                layout = new ArrayList<>(parts);
                importOutliner(layout);
            }
            if (layout.isEmpty()) {
                throw new IllegalStateException("Model declares no parts");
            }
            float top = 0.0f;
            Map<String, String> usedBy = new LinkedHashMap<>();
            for (PartSpec spec : layout) {
                for (Element element : spec.elements(elementsByName, elementsByUuid)) {
                    String previous = usedBy.putIfAbsent(element.uuid() == null
                            ? element.name() : element.uuid(), spec.name());
                    if (previous != null) {
                        throw new IllegalArgumentException("Element '" + element.name()
                                + "' is claimed by both '" + previous + "' and '" + spec.name() + "'");
                    }
                    top = Math.max(top, element.toY() + Math.max(0.0f, element.inflate()));
                }
            }
            int height = modelHeightPx > 0 ? modelHeightPx : Math.max(1, (int) Math.ceil(top));
            List<MobPart> built = new ArrayList<>(layout.size());
            for (PartSpec spec : layout) {
                MobPart.Role role = roles.getOrDefault(spec.name(), spec.role());
                built.add(spec.toPart(elementsByName, elementsByUuid, height, role));
            }
            CustomMobModel.Builder model = CustomMobModel.builder(textureWidth, textureHeight, texturePng)
                    .modelHeightPx(height)
                    .textureName(textureName);
            for (MobPart part : built) {
                model.part(part);
            }
            for (MobAnimation animation : readAnimations()) {
                model.animation(animation);
            }
            return model.build();
        }

        /**
         * Appends one part per outliner group, depth-first so a parent always
         * precedes its children. Elements the outliner never claims are kept in
         * one synthetic part instead of being silently dropped.
         */
        private void importOutliner(List<PartSpec> layout) {
            if (outliner == null) {
                return;
            }
            Set<String> claimed = new LinkedHashSet<>();
            Map<String, Integer> indexByUuid = new LinkedHashMap<>();
            for (JsonElement node : outliner) {
                appendNode(node, -1, layout, indexByUuid, claimed);
            }
            List<String> orphans = new ArrayList<>();
            for (Element element : elementsByName.values()) {
                String key = element.uuid() == null ? element.name() : element.uuid();
                if (!claimed.contains(key)) {
                    orphans.add(element.name());
                }
            }
            if (!orphans.isEmpty()) {
                String name = uniqueName("unclaimed", layout);
                layout.add(new PartSpec(name, MobPart.Role.STATIC, 0, 0, 0, 0, 0, 0, -1, orphans, List.of()));
                DebugLogger.warning("[CustomMobs] " + orphans.size()
                        + " element(s) are outside the outliner; they render as part '" + name + "'");
            }
        }

        private void appendNode(JsonElement node, int parentIndex, List<PartSpec> layout,
                                Map<String, Integer> indexByUuid, Set<String> claimed) {
            if (node == null || !node.isJsonObject()) {
                return;
            }
            JsonObject group = node.getAsJsonObject();
            String uuid = string(group, "uuid");
            Group meta = uuid == null ? null : groupsByUuid.get(uuid);
            String name = meta != null && meta.name() != null && !meta.name().isBlank()
                    ? meta.name()
                    : string(group, "name");
            float[] origin = meta != null ? meta.origin() : vector(group, "origin", new float[]{0, 0, 0});
            float[] rotation = meta != null ? meta.rotation() : vector(group, "rotation", new float[]{0, 0, 0});
            String partName = uniqueName(MobAnimation.normalizePart(name), layout);
            List<String> elementNames = new ArrayList<>();
            List<String> elementUuids = new ArrayList<>();
            JsonElement children = group.get("children");
            if (children != null && children.isJsonArray()) {
                for (JsonElement child : children.getAsJsonArray()) {
                    if (child.isJsonPrimitive()) {
                        String elementUuid = child.getAsString();
                        Element element = elementsByUuid.get(elementUuid);
                        if (element != null) {
                            elementUuids.add(elementUuid);
                            elementNames.add(element.name());
                        }
                    }
                }
            }
            int index = layout.size();
            layout.add(new PartSpec(partName, MobPart.Role.STATIC,
                    origin[0], origin[1], origin[2], rotation[0], rotation[1], rotation[2],
                    parentIndex, elementNames, elementUuids));
            for (String elementUuid : elementUuids) {
                claimed.add(elementUuid);
            }
            if (uuid != null) {
                indexByUuid.put(uuid, index);
            }
            if (children != null && children.isJsonArray()) {
                for (JsonElement child : children.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        appendNode(child, index, layout, indexByUuid, claimed);
                    }
                }
            }
        }

        private static String uniqueName(String name, List<PartSpec> layout) {
            String base = name == null || name.isBlank() ? "part" : name;
            String candidate = base.isEmpty() ? "part" : base;
            int suffix = 1;
            while (usedName(candidate, layout)) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }

        private static boolean usedName(String name, List<PartSpec> layout) {
            for (PartSpec spec : layout) {
                if (spec.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private List<MobAnimation> readAnimations() {
            List<MobAnimation> clips = new ArrayList<>();
            if (animationJson == null) {
                return clips;
            }
            for (JsonElement entry : animationJson) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject clip = entry.getAsJsonObject();
                String name = string(clip, "name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                float length = Math.min(MAX_CLIP_SECONDS, Math.max(0.05f, number(clip, "length", 1.0f)));
                MobAnimation.Builder builder = MobAnimation.builder(name)
                        .lengthSeconds(length)
                        .loop(loops(clip));
                JsonElement animators = clip.get("animators");
                if (animators != null && animators.isJsonArray()) {
                    for (JsonElement animator : animators.getAsJsonArray()) {
                        readAnimator(animator, builder);
                    }
                } else if (animators != null && animators.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> animator : animators.getAsJsonObject().entrySet()) {
                        readAnimator(animator.getValue(), builder);
                    }
                }
                MobAnimation animation = builder.build();
                if (animation.hasTracks()) {
                    clips.add(animation);
                }
            }
            return clips;
        }

        private void readAnimator(JsonElement entry, MobAnimation.Builder builder) {
            if (entry == null || !entry.isJsonObject()) {
                return;
            }
            JsonObject animator = entry.getAsJsonObject();
            String type = string(animator, "type");
            if (type != null && !"bone".equalsIgnoreCase(type)) {
                return;
            }
            String partName = string(animator, "name");
            if (partName == null || partName.isBlank()) {
                Group group = groupsByUuid.get(string(animator, "uuid"));
                partName = group == null ? null : group.name();
            }
            if (partName == null || partName.isBlank()) {
                return;
            }
            JsonElement keyframes = animator.get("keyframes");
            if (keyframes == null || !keyframes.isJsonArray()) {
                return;
            }
            for (JsonElement frame : keyframes.getAsJsonArray()) {
                if (!frame.isJsonObject()) {
                    continue;
                }
                JsonObject keyframe = frame.getAsJsonObject();
                MobAnimation.Channel channel = channel(string(keyframe, "channel"));
                if (channel == null) {
                    continue;
                }
                float time = number(keyframe, "time", 0.0f);
                boolean step = "step".equalsIgnoreCase(string(keyframe, "interpolation"));
                JsonElement points = keyframe.get("data_points");
                if (points == null || !points.isJsonArray() || points.getAsJsonArray().isEmpty()) {
                    continue;
                }
                JsonElement point = points.getAsJsonArray().get(0);
                if (point == null || !point.isJsonObject()) {
                    continue;
                }
                JsonObject value = point.getAsJsonObject();
                builder.key(partName, channel, time,
                        scalar(value, "x"), scalar(value, "y"), scalar(value, "z"), step);
            }
        }

        private static MobAnimation.Channel channel(String name) {
            if (name == null) {
                return null;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "rotation" -> MobAnimation.Channel.ROTATION;
                case "position" -> MobAnimation.Channel.POSITION;
                default -> null;
            };
        }
    }

    private record PartSpec(
            String name,
            MobPart.Role role,
            float pivotX,
            float pivotY,
            float pivotZ,
            float rotationX,
            float rotationY,
            float rotationZ,
            int parent,
            List<String> elementNames,
            List<String> elementUuids
    ) {

        List<Element> elements(Map<String, Element> byName, Map<String, Element> byUuid) {
            List<Element> resolved = new ArrayList<>(elementNames.size());
            if (!elementUuids.isEmpty()) {
                for (String uuid : elementUuids) {
                    Element element = byUuid.get(uuid);
                    if (element == null) {
                        throw new IllegalArgumentException("Part '" + name
                                + "' references unknown element uuid '" + uuid + "'");
                    }
                    resolved.add(element);
                }
                return resolved;
            }
            for (String elementName : elementNames) {
                Element element = byName.get(elementName);
                if (element == null) {
                    throw new IllegalArgumentException("Part '" + name
                            + "' references unknown or unusable element '" + elementName + "'");
                }
                resolved.add(element);
            }
            return resolved;
        }

        MobPart toPart(Map<String, Element> byName, Map<String, Element> byUuid,
                       int modelHeightPx, MobPart.Role resolvedRole) {
            // MobPart pivots are y-down from the model's ground line; the
            // caller declares them y-up from the feet, as authored. A group's
            // rest rotation is authored y-up too, while MobPart stores the
            // vanilla y-down convention the renderer composes with, so the
            // horizontal-axis signs flip and the vertical axis keeps its own.
            MobPart.Builder part = MobPart.builder(name, resolvedRole)
                    .parent(parent)
                    .pivot(pivotX, modelHeightPx - pivotY, pivotZ)
                    .rotation(-rotationX, rotationY, -rotationZ);
            for (Element element : elements(byName, byUuid)) {
                float inflate = Math.max(0.0f, element.inflate());
                if (element.rotatedFaces()) {
                    DebugLogger.warning("[CustomMobs] Element '" + element.name()
                            + "' has a rotated face UV; the rotation is ignored");
                }
                MobBox.Builder box = MobBox.builder(0, 0)
                        .from(element.fromX() - inflate - pivotX,
                                pivotY - (element.toY() + inflate),
                                element.fromZ() - inflate - pivotZ)
                        .to(element.toX() + inflate - pivotX,
                                pivotY - (element.fromY() - inflate),
                                element.toZ() + inflate - pivotZ)
                        .faceUvs(element.faceUvs());
                char axis = element.rotationAxis();
                if (axis != 0) {
                    // The rig's part space is the model's own y-up space, so an
                    // element rotation keeps its authored angle; only the pivot
                    // is subtracted (and the y flip cancels in the render).
                    box.rotation(axis, element.rotationAngle(),
                            element.originX() - pivotX,
                            element.originY() - pivotY,
                            element.originZ() - pivotZ);
                }
                part.box(box.build());
            }
            return part.build();
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException notAString) {
            return null;
        }
    }

    private static float[] vector(JsonObject object, String key, float[] fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() < 3) {
            return fallback;
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float number(JsonObject object, String key, float fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsFloat();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    /**
     * Whether a clip loops. Blockbench writes {@code loop} as the string
     * {@code "loop"}, {@code "hold"} or {@code "once"}; a plain boolean is
     * accepted too. Only {@code "loop"} (and {@code true}) wrap the playhead —
     * the other two clamp at the last keyframe.
     */
    private static boolean loops(JsonObject clip) {
        JsonElement value = clip == null ? null : clip.get("loop");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return false;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return "loop".equalsIgnoreCase(value.getAsString().trim());
    }

    /**
     * A keyframe value; molang expressions are not evaluated, so anything that
     * is not a plain number contributes {@code 0}.
     */
    private static float scalar(JsonObject point, String key) {
        if (point == null || !point.has(key) || point.get(key).isJsonNull()) {
            return 0.0f;
        }
        try {
            return point.get(key).getAsFloat();
        } catch (RuntimeException expression) {
            return 0.0f;
        }
    }
}
