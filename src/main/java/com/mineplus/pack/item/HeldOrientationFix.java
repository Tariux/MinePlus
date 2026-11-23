package com.mineplus.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Dynamic hand-fit for item models, computed from each model's own geometry.
 *
 * <p>Every item model lives somewhere inside its 0..16 model space, and every
 * model has a bounding space that fits it. Instead of hand-tuned display
 * numbers per gun, this pass derives the held pose from that space: the grip
 * anchor is the model's grip cube (where the hand meets the weapon, falling
 * back to the lowest cube) and the facing comes from the grip-to-muzzle ray
 * of named cubes. A model whose hand contexts present the muzzle at the
 * viewer keeps its exact grip position — the author placed the weapon in the
 * hand correctly, only the facing is wrong — and is turned around about that
 * anchor: the yaw flips by 180° and the translation is re-solved as
 * {@code T1 = T0 + R0·(s·anchor) − R1·(s·anchor)}, so the rendered grip point
 * is unchanged while the muzzle now points away from the viewer.</p>
 *
 * <p>Models whose facing cannot be established (no named muzzle end) or whose
 * contexts already face forward are left untouched, and every hand context is
 * judged alone.</p>
 */
public final class HeldOrientationFix {

    /** Element-name fragments that mark the grip end of a weapon. "butt" is
     * deliberately absent: a butt plate or stock butt is the shoulder end, the
     * opposite of where the hand grips. */
    private static final String[] GRIP_KEYS = {
        "grip", "handle", "stock", "comb", "wrist"};

    /** Element-name fragments that mark the muzzle end of a weapon. */
    private static final String[] MUZZLE_KEYS = {
        "muzzle", "crown", "barrel", "sight", "bead", "front"};

    /** The contexts a hand can hold an item in; each is checked and fitted alone. */
    private static final String[] HAND_CONTEXTS = {
        "firstperson_righthand", "firstperson_lefthand",
        "thirdperson_righthand", "thirdperson_lefthand"};

    private HeldOrientationFix() {
    }

    /**
     * Walks {@code root} recursively and refits every item model — the idle
     * and every animation frame alike — whose hand contexts present the muzzle
     * at the viewer. Frames matter as much as the idle: a gun spends its whole
     * life cycling through frames, so an idle-only repair leaves the weapon
     * visually backward whenever any frame is on screen.
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
        int fixed = 0;
        for (File file : models) {
            if (fix(log, file)) {
                fixed++;
            }
        }
        if (fixed > 0) {
            log.info("[HeldOrientationFix] Re-fitted " + fixed + " model(s) whose hand"
                    + " pose presented the muzzle at the viewer.");
        }
        return fixed;
    }

    /**
     * Refits one model file in place.
     *
     * @return whether the file was rewritten
     */
    public static boolean fix(Logger log, File file) {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
        } catch (Exception malformed) {
            log.warning("[HeldOrientationFix] Skipping " + file.getName()
                    + ": not a readable model (" + malformed.getMessage() + ")");
            return false;
        }
        JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                ? root.getAsJsonArray("elements") : null;
        if (elements == null || elements.size() == 0) {
            return false;
        }
        float[] ray = gripToMuzzle(elements);
        float[] anchor = gripAnchor(elements);
        if (ray == null || anchor == null) {
            return false;
        }
        JsonObject display = root.has("display") && root.get("display").isJsonObject()
                ? root.getAsJsonObject("display") : null;
        if (display == null) {
            return false;
        }
        boolean changed = false;
        for (String context : HAND_CONTEXTS) {
            if (!display.has(context) || !display.get(context).isJsonObject()) {
                continue;
            }
            JsonObject pose = display.getAsJsonObject(context);
            float[] rotation = floatArray(pose, "rotation");
            float[] translation = floatArray(pose, "translation");
            float[] world = applyRotation(rotation, ray);
            if (world[2] <= 0.1f) {
                continue;
            }
            float uniformScale = floatArray(pose, "scale")[0];
            float[] scaled = {anchor[0] * uniformScale, anchor[1] * uniformScale, anchor[2] * uniformScale};
            float[] originalGrip = add(translation, applyRotation(rotation, scaled));
            float[] newRotation = {rotation[0], rotation[1] + 180f, rotation[2]};
            float[] keptGrip = applyRotation(newRotation, scaled);
            JsonArray newRotationJson = new JsonArray();
            JsonArray newTranslation = new JsonArray();
            for (int i = 0; i < 3; i++) {
                newRotationJson.add(round(newRotation[i]));
                newTranslation.add(round(originalGrip[i] - keptGrip[i]));
            }
            pose.add("rotation", newRotationJson);
            pose.add("translation", newTranslation);
            changed = true;
            log.info("[HeldOrientationFix] " + file.getName() + " " + context
                    + ": muzzle pointed at the viewer (z=+" + String.format(Locale.ROOT, "%.2f", world[2])
                    + "); turned 180° about the grip anchor — grip position kept, muzzle forward.");
        }
        if (!changed) {
            return false;
        }
        try {
            Files.writeString(file.toPath(), root.toString());
        } catch (IOException failure) {
            log.warning("[HeldOrientationFix] Could not write " + file.getName() + ": " + failure.getMessage());
            return false;
        }
        return true;
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
            if (grip == null && matches(name, GRIP_KEYS)) {
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
     * Where the hand meets the weapon: the centre of a named grip cube when
     * the model has one, otherwise the lowest cube. A model's lowest cube is
     * not always the grip — a trigger guard or butt cap can sit lower — so
     * the named cube wins when present.
     */
    private static float[] gripAnchor(JsonArray elements) {
        float[] grip = null;
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
            if (grip == null && matches(name, GRIP_KEYS)) {
                grip = centre;
            }
        }
        return grip != null ? grip : lowest;
    }

    private static float[] cubeCentre(JsonObject element) {
        float[] from = floatArray(element, "from");
        float[] to = floatArray(element, "to");
        return new float[]{(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    /** Display rotation applied as Rx then Ry then Rz (extrinsic XYZ). */
    private static float[] applyRotation(float[] rotation, float[] v) {
        float[] w = v.clone();
        w = axis(0, rotation[0]).apply(w);
        w = axis(1, rotation[1]).apply(w);
        w = axis(2, rotation[2]).apply(w);
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
