package com.mineplus.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Dynamic hand-fit and display-fit for item models, computed from each
 * model's own geometry.
 *
 * <p>Every item model lives somewhere inside its model space, and every model
 * has a bounding space that fits it. Instead of hand-tuned display numbers
 * per gun, this pass derives each display context from that space:</p>
 *
 * <ul>
 *   <li><b>Hand contexts</b> (first person and third person, both hands): the
 *   grip anchor is the model's grip cube — the named grip-family cube nearest
 *   the trigger cube, falling back to the trigger itself, then to the lowest
 *   cube — and the facing comes from the grip-to-muzzle ray of named cubes. A
 *   context whose muzzle presents at the viewer is turned 180° about the grip
 *   anchor. A gun whose grip lands too far from the hand point is moved so
 *   the grip sits in the fist: the hand points come from vanilla's own
 *   {@code handheld.json} (where Mojang puts the grip of a vanilla tool) and
 *   were validated against this pack's well-placed guns. Animation frames
 *   keep their authored offsets: the correction is measured on the gun's
 *   reference model (the idle) and applied as one shared delta, so fire,
 *   loading and scope frames still move exactly as authored.</li>
 *   <li><b>Item contexts</b> ({@code gui} inventory slot, {@code ground}
 *   dropped item, {@code fixed} item frame, {@code head}): a missing context
 *   is synthesized from the bounding box (centred, scaled to fit the slot);
 *   a present context whose bounding box centre lands far from the slot
 *   centre is re-centred without touching its authored rotation.</li>
 * </ul>
 *
 * <p>Models whose facing cannot be established and whose grips already sit at
 * the hand point are left untouched, and every context is judged alone.</p>
 */
public final class HeldOrientationFix {

    /** Element-name fragments that mark the grip end of a weapon. Shoulder-end
     * names ("stock_butt", "stock_comb", "bolt_handle") match these fragments
     * too, so {@link #NOT_GRIP} filters them out first. */
    private static final String[] GRIP_KEYS = {
        "grip", "wrist", "neck", "handle"};

    /** Element-name fragments that disqualify a candidate grip cube: they name
     * the shoulder end or hardware far from the hand. */
    private static final String[] NOT_GRIP = {
        "butt", "plate", "toe", "heel", "comb", "cheek", "panel",
        "bolt", "sling", "scope", "swivel", "mag", "cap"};

    /** Element-name fragments that mark the muzzle end of a weapon. */
    private static final String[] MUZZLE_KEYS = {
        "muzzle", "crown", "barrel", "sight", "bead", "front"};

    /** The contexts a hand can hold an item in; each is checked and fitted alone. */
    private static final String[] HAND_CONTEXTS = {
        "firstperson_righthand", "firstperson_lefthand",
        "thirdperson_righthand", "thirdperson_lefthand"};

    /** The contexts where the item stands alone (slot, ground, frame, head). */
    private static final String[] ITEM_CONTEXTS = {"gui", "ground", "fixed", "head"};

    /**
     * Where the fist sits in display space, calibrated from vanilla
     * {@code handheld.json} (the shipped rotations, translations and scales)
     * applied to the grip point of a vanilla tool (model space (2.5, 3.5, 8)).
     * The pack's own well-placed guns put their grips within about 1.5 to 5
     * units of this point, which fixes the calibration against the mounts the
     * game actually uses; the same point serves both hands because the pack's
     * displays are identical for both.
     */
    private static final float[] HAND_POINT_FIRST = {6.6f, 4.6f, -1.4f};
    private static final float[] HAND_POINT_THIRD = {6.8f, 4.0f, -3.2f};

    /** A first-person grip further than this from the fist is refitted; closer
     * grips belong to guns the authors already placed well and are untouched. */
    private static final float FIT_TOLERANCE_FIRST = 8.0f;

    /** Third-person grips sit further out across the whole pack (every gun
     * floated away from the body), so the tolerance is tighter. */
    private static final float FIT_TOLERANCE_THIRD = 4.0f;

    /** An item context whose bounding-box centre lands further than this from
     * its slot centre is re-centred. */
    private static final float RECENTRE_TOLERANCE = 4.0f;

    /** Slot size limits used when synthesizing a missing item context, in
     * model units: the fitted model must not overflow the slot. */
    private static final float GUI_LIMIT = 16f;
    private static final float GROUND_LIMIT = 12f;
    private static final float FIXED_LIMIT = 12f;
    private static final float HEAD_LIMIT = 16f;

    /** Suffixes that mark an animation frame; the frame-free stem is the gun's
     * group key and the reference model the frames are fitted against. */
    private static final String FRAME_SUFFIX =
        "_(fire|primed|idle|scope|reload|loading|draw|inspect|cycle|charge|ready)(_\\d+)?$";

    private HeldOrientationFix() {
    }

    /**
     * Walks {@code root} recursively, groups the models into guns and their
     * animation frames, and refits every display context as described above.
     * Frames matter as much as the idle: a gun spends its whole life cycling
     * through frames, so an idle-only repair leaves the weapon misplaced
     * whenever any frame is on screen.
     *
     * @return how many models were rewritten
     */
    public static int fixAll(Logger log, File root) {
        if (root == null || !root.isDirectory()) {
            return 0;
        }
        List<File> models = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> models.add(p.toFile()));
        } catch (IOException failure) {
            log.warning("[HeldOrientationFix] Could not walk " + root + ": " + failure.getMessage());
            return 0;
        }
        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (File file : models) {
            String stem = file.getName().replaceAll("\\.json$", "");
            groups.computeIfAbsent(stem.replaceAll(FRAME_SUFFIX, ""), k -> new ArrayList<>()).add(file);
        }
        int fixed = 0;
        for (List<File> group : groups.values()) {
            fixed += fixGroup(log, group);
        }
        if (fixed > 0) {
            log.info("[HeldOrientationFix] Re-fitted " + fixed + " model(s) whose display"
                    + " poses placed the weapon off the hand or the item off its slot.");
        }
        return fixed;
    }

    private static int fixGroup(Logger log, List<File> group) {
        List<ParsedModel> parsed = new ArrayList<>();
        for (File file : group) {
            ParsedModel model = parse(file);
            if (model != null) {
                parsed.add(model);
            }
        }
        if (parsed.isEmpty()) {
            return 0;
        }
        ParsedModel reference = referenceOf(parsed);
        int fixed = 0;
        for (ParsedModel model : parsed) {
            JsonObject display = model.root.has("display") && model.root.get("display").isJsonObject()
                    ? model.root.getAsJsonObject("display") : null;
            boolean changed;
            if (display != null) {
                changed = fitHands(log, reference, model, display);
                changed |= fitItemContexts(log, model, display);
            } else {
                // A model without any display still gets the standalone slots,
                // synthesized from its bounding box.
                model.root.add("display", new JsonObject());
                changed = fitItemContexts(log, model, model.root.getAsJsonObject("display"));
            }
            if (changed && write(model)) {
                fixed++;
            }
        }
        return fixed;
    }

    /** The idle model when the gun has one, else the frame-free base model,
     * else the first file — the frames are fitted relative to it. */
    private static ParsedModel referenceOf(List<ParsedModel> parsed) {
        for (ParsedModel model : parsed) {
            if (model.file.getName().replaceAll("\\.json$", "").endsWith("_idle")) {
                return model;
            }
        }
        for (ParsedModel model : parsed) {
            if (!model.file.getName().replaceAll("\\.json$", "").contains("_")) {
                return model;
            }
        }
        return parsed.get(0);
    }

    private static boolean fitHands(Logger log, ParsedModel reference, ParsedModel model, JsonObject display) {
        boolean changed = false;
        for (String context : HAND_CONTEXTS) {
            if (!display.has(context) || !display.get(context).isJsonObject()) {
                continue;
            }
            JsonObject pose = display.getAsJsonObject(context);
            float[] rotation = floatArray(pose, "rotation");
            float[] translation = floatArray(pose, "translation");
            float[] scale = scaleArray(pose);
            float[] anchor = gripAnchor(model.elements);
            if (anchor == null) {
                continue;
            }

            // Facing: a muzzle presented at the viewer is turned 180° about the
            // grip anchor, keeping the rendered grip point.
            float[] ray = gripToMuzzle(model.elements);
            float[] gripWorld = world(translation, rotation, scale, anchor);
            if (ray != null && applyRotation(rotation, ray)[2] > 0.1f) {
                float[] flipped = {rotation[0], rotation[1] + 180f, rotation[2]};
                translation = keepGrip(translation, rotation, flipped, scale, anchor);
                rotation = flipped;
                gripWorld = world(translation, rotation, scale, anchor);
                setRotation(pose, rotation);
                changed = true;
                log.info("[HeldOrientationFix] " + model.file.getName() + " " + context
                        + ": muzzle pointed at the viewer; turned 180° about the grip anchor.");
            }

            // Placement: move the grip onto the hand point when the reference
            // model sits outside the tolerance; frames share the correction so
            // the authored animation offsets survive.
            float[] handPoint = context.startsWith("third") ? HAND_POINT_THIRD : HAND_POINT_FIRST;
            float tolerance = context.startsWith("third") ? FIT_TOLERANCE_THIRD : FIT_TOLERANCE_FIRST;
            if (model == reference) {
                float deviation = distance(gripWorld, handPoint);
                if (deviation > tolerance) {
                    reference.gripDelta.put(context, subtract(handPoint, gripWorld));
                    log.info("[HeldOrientationFix] " + model.file.getName() + " " + context
                            + ": grip sat " + String.format(Locale.ROOT, "%.1f", deviation)
                            + " units from the hand; moved into the fist.");
                }
            }
            float[] delta = reference.gripDelta.get(context);
            if (delta != null) {
                float[] desired = add(gripWorld, delta);
                setTranslation(pose, subtract(desired,
                        world(new float[3], rotation, scale, anchor)));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Synthesizes or re-centres the standalone contexts: {@code gui} (the
     * inventory slot), {@code ground} (the dropped item), {@code fixed} (the
     * item frame) and {@code head}. A missing context is created centred and
     * scaled to its slot; a present context keeps its authored rotation and
     * is only re-centred when the bounding box landed far off.
     */
    private static boolean fitItemContexts(Logger log, ParsedModel model, JsonObject display) {
        float[] bounds = bounds(model.elements);
        if (bounds == null) {
            return false;
        }
        boolean changed = false;
        float[] centre = {(bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f, (bounds[2] + bounds[5]) / 2f};
        for (String context : ITEM_CONTEXTS) {
            float limit = switch (context) {
                case "gui" -> GUI_LIMIT;
                case "ground" -> GROUND_LIMIT;
                case "fixed" -> FIXED_LIMIT;
                default -> HEAD_LIMIT;
            };
            float[] slotCentre = context.equals("ground") ? new float[]{0f, 2f, 0f} : new float[3];
            JsonObject pose;
            if (!display.has(context) || !display.get(context).isJsonObject()) {
                pose = new JsonObject();
                float[] rotation = context.equals("gui") ? new float[]{30f, 225f, 0f} : new float[3];
                float fitted = Math.min(1f, limit / Math.max(0.01f, extent(rotation, bounds)));
                setRotation(pose, rotation);
                setScale(pose, fitted);
                setTranslation(pose, subtract(slotCentre, world(new float[3], rotation,
                        new float[]{fitted, fitted, fitted}, centre)));
                display.add(context, pose);
                changed = true;
                log.info("[HeldOrientationFix] " + model.file.getName() + " " + context
                        + ": synthesized from the bounding box (scale "
                        + String.format(Locale.ROOT, "%.2f", fitted) + ").");
                continue;
            }
            pose = display.getAsJsonObject(context);
            float[] rotation = floatArray(pose, "rotation");
            float[] scale = scaleArray(pose);
            float[] worldCentre = world(floatArray(pose, "translation"), rotation, scale, centre);
            if (distance(worldCentre, slotCentre) > RECENTRE_TOLERANCE) {
                setTranslation(pose, add(floatArray(pose, "translation"),
                        subtract(slotCentre, worldCentre)));
                changed = true;
                log.info("[HeldOrientationFix] " + model.file.getName() + " " + context
                        + ": re-centred in its slot.");
            }
        }
        return changed;
    }

    private static ParsedModel parse(File file) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                    ? root.getAsJsonArray("elements") : null;
            if (elements == null || elements.size() == 0) {
                return null;
            }
            return new ParsedModel(file, root, elements);
        } catch (Exception malformed) {
            return null;
        }
    }

    private static boolean write(ParsedModel model) {
        try {
            Files.writeString(model.file.toPath(), model.root.toString());
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private static final class ParsedModel {
        final File file;
        final JsonObject root;
        final JsonArray elements;
        final Map<String, float[]> gripDelta = new LinkedHashMap<>();

        ParsedModel(File file, JsonObject root, JsonArray elements) {
            this.file = file;
            this.root = root;
            this.elements = elements;
        }
    }

    /** The unit grip→muzzle ray, or null when the names do not establish facing. */
    private static float[] gripToMuzzle(JsonArray elements) {
        float[] grip = null;
        float[] muzzle = null;
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject element = elements.get(i).getAsJsonObject();
            if (!element.has("from") || !element.has("to")) {
                continue;
            }
            String name = element.has("name") && !element.get("name").isJsonNull()
                    ? element.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
            if (grip == null && matches(name, GRIP_KEYS) && !matches(name, NOT_GRIP)) {
                grip = cubeCentre(element);
            }
            if (muzzle == null && matches(name, MUZZLE_KEYS)) {
                muzzle = cubeCentre(element);
            }
        }
        if (grip == null || muzzle == null) {
            return null;
        }
        float dx = muzzle[0] - grip[0];
        float dy = muzzle[1] - grip[1];
        float dz = muzzle[2] - grip[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 4f) {
            return null;
        }
        return new float[]{dx / length, dy / length, dz / length};
    }

    private static boolean matches(String name, String[] keys) {
        for (String key : keys) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the hand meets the weapon: the named grip-family cube nearest the
     * trigger when the model has one, the trigger cube itself otherwise, and
     * the lowest cube as the last resort. A model's lowest cube is not always
     * the grip — a butt plate or magazine can sit lower — so a named cube wins
     * when present. Null only when the model has no cubes at all.
     */
    private static float[] gripAnchor(JsonArray elements) {
        float[] trigger = null;
        float[] grip = null;
        float gripDistance = Float.MAX_VALUE;
        float[] lowest = null;
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject element = elements.get(i).getAsJsonObject();
            if (!element.has("from") || !element.has("to")) {
                continue;
            }
            float[] centre = cubeCentre(element);
            if (lowest == null || centre[1] < lowest[1]) {
                lowest = centre;
            }
            String name = element.has("name") && !element.get("name").isJsonNull()
                    ? element.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
            if (trigger == null && name.contains("trigger")) {
                trigger = centre;
            }
            if (matches(name, GRIP_KEYS) && !matches(name, NOT_GRIP)) {
                float distance = trigger != null ? distance(centre, trigger) : 0f;
                if (grip == null || distance < gripDistance) {
                    grip = centre;
                    gripDistance = distance;
                }
            }
        }
        if (grip != null) {
            return grip;
        }
        return trigger != null ? trigger : lowest;
    }

    private static float[] bounds(JsonArray elements) {
        float[] out = null;
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject element = elements.get(i).getAsJsonObject();
            if (!element.has("from") || !element.has("to")) {
                continue;
            }
            float[] from = floatArray(element, "from");
            float[] to = floatArray(element, "to");
            float[] lo = {Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])};
            float[] hi = {Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])};
            if (out == null) {
                out = new float[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
            } else {
                for (int a = 0; a < 3; a++) {
                    out[a] = Math.min(out[a], lo[a]);
                    out[a + 3] = Math.max(out[a + 3], hi[a]);
                }
            }
        }
        return out;
    }

    /** The largest reach of the bounding box corners after rotation — the size
     * the model presents to its slot. */
    private static float extent(float[] rotation, float[] bounds) {
        float max = 0f;
        for (int corner = 0; corner < 8; corner++) {
            float[] v = {
                (corner & 1) != 0 ? bounds[3] : bounds[0],
                (corner & 2) != 0 ? bounds[4] : bounds[1],
                (corner & 4) != 0 ? bounds[5] : bounds[2]};
            float[] world = applyRotation(rotation, v);
            for (float value : world) {
                max = Math.max(max, Math.abs(value));
            }
        }
        return max;
    }

    private static float[] world(float[] translation, float[] rotation, float[] scale, float[] point) {
        float[] scaled = {point[0] * scale[0], point[1] * scale[1], point[2] * scale[2]};
        return add(translation, applyRotation(rotation, scaled));
    }

    /** The translation that keeps {@code anchor} at its current rendered spot
     * while the rotation changes from {@code from} to {@code to}. */
    private static float[] keepGrip(float[] translation, float[] from, float[] to, float[] scale, float[] anchor) {
        float[] current = world(translation, from, scale, anchor);
        return subtract(current, world(new float[3], to, scale, anchor));
    }

    private static float[] cubeCentre(JsonObject element) {
        float[] from = floatArray(element, "from");
        float[] to = floatArray(element, "to");
        return new float[]{(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] subtract(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Display rotation applied as the matrix Rx·Ry·Rz — on the vector that is
     * Z first, then Y, then X, the composition Minecraft's
     * {@code rotationXYZ} quaternion and Blockbench both use for item
     * displays. The hand-point calibration constants assume this order. */
    private static float[] applyRotation(float[] rotation, float[] v) {
        float[] w = v.clone();
        w = axis(2, rotation[2]).apply(w);
        w = axis(1, rotation[1]).apply(w);
        w = axis(0, rotation[0]).apply(w);
        return w;
    }

    private interface Axis {
        float[] apply(float[] v);
    }

    private static Axis axis(int axis, float degrees) {
        double radians = Math.toRadians(degrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        return switch (axis) {
            case 0 -> v -> new float[]{v[0], c * v[1] - s * v[2], s * v[1] + c * v[2]};
            case 1 -> v -> new float[]{c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]};
            default -> v -> new float[]{c * v[0] - s * v[1], s * v[0] + c * v[1], v[2]};
        };
    }

    private static float[] scaleArray(JsonObject pose) {
        if (!pose.has("scale") || !pose.get("scale").isJsonArray()) {
            return new float[]{1f, 1f, 1f};
        }
        JsonArray array = pose.getAsJsonArray("scale");
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = i < array.size() && !array.get(i).isJsonNull() ? array.get(i).getAsFloat() : 1f;
        }
        return out;
    }

    private static void setRotation(JsonObject pose, float[] rotation) {
        JsonArray array = new JsonArray();
        for (float value : rotation) {
            array.add(round(value));
        }
        pose.add("rotation", array);
    }

    private static void setTranslation(JsonObject pose, float[] translation) {
        JsonArray array = new JsonArray();
        for (float value : translation) {
            array.add(round(value));
        }
        pose.add("translation", array);
    }

    private static void setScale(JsonObject pose, float scale) {
        JsonArray array = new JsonArray();
        array.add(round(scale));
        array.add(round(scale));
        array.add(round(scale));
        pose.add("scale", array);
    }

    private static float[] floatArray(JsonObject object, String key) {
        float[] out = new float[3];
        if (object.has(key) && object.get(key).isJsonArray()) {
            JsonArray array = object.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < array.size(); i++) {
                if (!array.get(i).isJsonNull()) {
                    out[i] = array.get(i).getAsFloat();
                }
            }
        }
        return out;
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }
}
