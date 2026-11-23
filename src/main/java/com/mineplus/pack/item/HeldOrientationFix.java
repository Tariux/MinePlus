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
 * Geometry-driven held-orientation repair for idle item models.
 *
 * <p>Some gun sources carry a display table whose hand contexts present the
 * muzzle toward the viewer instead of away from it — a template authored for
 * opposite-facing geometry and applied verbatim because it is "authored". This
 * pass reads the model's own grip-to-muzzle ray from named cubes, rotates it by
 * each hand context's display rotation, and when the muzzle still points at
 * the viewer (world +Z) it composes a 180° yaw about the model's geometric
 * centre so the weapon points forward while staying where the author put it.</p>
 *
 * <p>Models whose facing cannot be established are left alone: the ray only
 * exists when the source names both a grip cube (grip, handle, stock, butt,
 * comb, wrist) and a muzzle-end cube (muzzle, crown, barrel, sight, bead,
 * front). Correct models therefore pass through untouched, and the check runs
 * per context so a pose that is already fine is never rewritten.</p>
 */
public final class HeldOrientationFix {

    /** Element-name fragments that mark the grip end of a weapon. */
    private static final String[] GRIP_KEYS = {
        "grip", "handle", "stock", "butt", "comb", "wrist"};

    /** Element-name fragments that mark the muzzle end of a weapon. */
    private static final String[] MUZZLE_KEYS = {
        "muzzle", "crown", "barrel", "sight", "bead", "front"};

    /** The contexts a hand can hold an item in; each is checked and fixed alone. */
    private static final String[] HAND_CONTEXTS = {
        "firstperson_righthand", "firstperson_lefthand",
        "thirdperson_righthand", "thirdperson_lefthand"};

    private HeldOrientationFix() {
    }

    /**
     * Walks {@code root} recursively and repairs every {@code *_idle.json}
     * whose hand contexts present the muzzle at the viewer.
     *
     * @return how many models were rewritten
     */
    public static int fixAll(Logger log, File root) {
        if (root == null || !root.isDirectory()) {
            return 0;
        }
        List<File> idles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(p -> p.getFileName().toString().endsWith("_idle.json"))
                    .forEach(p -> idles.add(p.toFile()));
        } catch (IOException failure) {
            log.warning("[HeldOrientationFix] Could not walk " + root + ": " + failure.getMessage());
            return 0;
        }
        int fixed = 0;
        for (File file : idles) {
            if (fix(log, file)) {
                fixed++;
            }
        }
        if (fixed > 0) {
            log.info("[HeldOrientationFix] Re-oriented " + fixed + " model(s) whose hand"
                    + " pose presented the muzzle at the viewer.");
        }
        return fixed;
    }

    /**
     * Repairs one model file in place.
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
        if (ray == null) {
            return false;
        }
        float[] centre = boundsCentre(elements);
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
            float[] scale = floatArray(pose, "scale");
            float[] world = applyRotation(rotation, ray);
            if (world[2] <= 0.1f) {
                continue;
            }
            float[] turned = rotation.clone();
            turned[1] = normalize(turned[1] + 180f);
            float[] kept = applyRotation(rotation, centre);
            float[] now = applyRotation(turned, centre);
            JsonArray newRotation = new JsonArray();
            JsonArray newTranslation = new JsonArray();
            for (int i = 0; i < 3; i++) {
                newRotation.add(round(turned[i]));
                newTranslation.add(round(translation[i] + scale[i] * (kept[i] - now[i])));
            }
            pose.add("rotation", newRotation);
            pose.add("translation", newTranslation);
            changed = true;
            log.info("[HeldOrientationFix] " + file.getName() + " " + context
                    + ": muzzle pointed at the viewer (z=+" + String.format(Locale.ROOT, "%.2f", world[2])
                    + "); added 180° yaw about the model centre.");
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

    private static float[] boundsCentre(JsonArray elements) {
        float[] low = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] high = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
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
            for (int a = 0; a < 3; a++) {
                low[a] = Math.min(low[a], Math.min(from[a], to[a]));
                high[a] = Math.max(high[a], Math.max(from[a], to[a]));
            }
        }
        return new float[]{(low[0] + high[0]) / 2f, (low[1] + high[1]) / 2f, (low[2] + high[2]) / 2f};
    }

    private static float[] cubeCentre(JsonObject element) {
        float[] from = floatArray(element, "from");
        float[] to = floatArray(element, "to");
        return new float[]{(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
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

    private static float normalize(float yaw) {
        float y = yaw % 360f;
        if (y > 180f) {
            y -= 360f;
        }
        if (y <= -180f) {
            y += 360f;
        }
        return y;
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }
}
