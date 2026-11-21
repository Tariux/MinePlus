package com.mineplus.pack.compile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.util.DebugLogger;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The client-window guard every compiled model passes through before it is
 * written into an artifact.
 *
 * <p>Minecraft's model element window is {@code -16..32} pixels on every axis.
 * A coordinate outside it is clamped by the client, the cube collapses and the
 * element renders the missing-texture checker — a corrupt model that is silent
 * server-side and looks like a broken texture client-side. Every writer shares
 * this one gate, so no authored or imported geometry can reach an artifact
 * outside the window again.</p>
 *
 * <p>The fix is a translation, not a squash: an element that overflows one axis
 * is slid back inside it, keeping its shape, its UV windows and (for a rotated
 * element) its rotation origin. An element whose own span exceeds the whole
 * window cannot be rescued by translation; its extents are clamped and the
 * model is reported, because that geometry can never render in one element.</p>
 *
 * <p>Runs over the artifact's final entry map, so it covers every generation
 * path — bone rigs, custom mob parts and pack blocks alike — and is not gated
 * by {@code PACK.VALIDATE_ASSETS}: a warning switch must never re-enable
 * corrupt geometry.</p>
 */
public final class ModelGeometryGuard {

    /** Lowest pixel coordinate the client's element window accepts. */
    public static final float WINDOW_MIN = -16.0f;
    /** Highest pixel coordinate the client's element window accepts. */
    public static final float WINDOW_MAX = 32.0f;
    /** Usable span of the window ({@code WINDOW_MAX - WINDOW_MIN}). */
    public static final float WINDOW_SPAN = WINDOW_MAX - WINDOW_MIN;

    /** How many moved models one report line describes before truncating. */
    private static final int MAX_FINDINGS = 64;

    private ModelGeometryGuard() {
    }

    /**
     * @param scanned   model entries examined
     * @param moved     models whose geometry had to be re-based
     * @param elements  elements translated back into the window
     * @param clamped   elements whose span exceeded the window and were squashed
     * @param findings  per-model notes, truncated at {@value #MAX_FINDINGS}
     */
    public record Report(int scanned, int moved, int elements, int clamped, int snappedModels,
                         int snappedAngles, List<String> findings) {

        public static final Report EMPTY = new Report(0, 0, 0, 0, 0, 0, List.of());

        public boolean clean() {
            return moved == 0 && clamped == 0;
        }
    }

    /** Element rotation step of clients older than 1.21.6. */
    public static final float LEGACY_ANGLE_STEP = 22.5f;
    /** Largest element rotation every client version accepts. */
    public static final float MAX_ANGLE = 45.0f;

    /**
     * Rebases every out-of-window element of every model entry in place.
     *
     * @param entries the artifact's entry map ({@code zip path -> bytes}); the
     *                map's keys are never touched, only the bytes of moved models
     * @return what had to change
     */
    public static Report enforce(Map<String, byte[]> entries) {
        return enforce(entries, false);
    }

    /**
     * As {@link #enforce(Map)}, and with {@code steppedAngles} also snaps every
     * element rotation to the {@value #LEGACY_ANGLE_STEP} degree steps a client
     * older than 1.21.6 requires. Such a client rejects the whole model for a
     * single free angle and renders the missing-model cube instead, so a
     * slightly coarser hinge is the only way that model can render at all.
     *
     * @param steppedAngles true when the artifact targets a client older than 1.21.6
     */
    public static Report enforce(Map<String, byte[]> entries, boolean steppedAngles) {
        if (entries == null || entries.isEmpty()) {
            return Report.EMPTY;
        }
        int scanned = 0;
        int movedModels = 0;
        int movedElements = 0;
        int clampedElements = 0;
        int snappedModels = 0;
        int snappedAngles = 0;
        List<String> snappedSamples = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        List<String> paths = new ArrayList<>(entries.keySet());
        for (String path : paths) {
            if (!isModelEntry(path)) {
                continue;
            }
            byte[] bytes = entries.get(path);
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            JsonObject root;
            try {
                JsonElement parsed = JsonParser.parseReader(new InputStreamReader(
                        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
                if (parsed == null || !parsed.isJsonObject()) {
                    continue;
                }
                root = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                // A file the guard cannot read is not the guard's business; the
                // validator reports unparseable JSON separately.
                continue;
            }
            if (!root.has("elements") || !root.get("elements").isJsonArray()) {
                continue;
            }
            scanned++;
            JsonArray elements = root.getAsJsonArray("elements");
            if (path.contains("/models/block/")) {
                // Block-axis models are uniformly fitted by the serializer
                // (one shared scale + translation, renderer-compensated); a
                // per-element rewrite here would scramble layout and stretch
                // UVs, so this axis is verification-only.
                continue;
            }
            int moved = 0;
            int squashed = 0;
            int snapped = 0;
            for (JsonElement element : elements) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject box = element.getAsJsonObject();
                int result = enforceElement(box, path);
                if (result > 1) {
                    moved++;
                } else if (result == 1) {
                    squashed++;
                }
                if (steppedAngles && snapAngle(box)) {
                    snapped++;
                }
            }
            if (snapped > 0) {
                snappedModels++;
                snappedAngles += snapped;
                if (snappedSamples.size() < 8) {
                    snappedSamples.add(path.substring(path.lastIndexOf('/') + 1) + " x" + snapped);
                }
            }
            if (moved == 0 && squashed == 0) {
                if (snapped > 0) {
                    entries.put(path, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                }
                continue;
            }
            movedModels++;
            movedElements += moved;
            clampedElements += squashed;
            StringBuilder note = new StringBuilder("Model '").append(path).append("' was re-based into the client");
            if (moved > 0) {
                note.append(" element window (").append(moved).append(" element(s) translated)");
            }
            if (squashed > 0) {
                note.append(moved > 0 ? " and " : " ").append(squashed)
                        .append(" element(s) wider than ").append((int) WINDOW_SPAN)
                        .append("px clamped");
            }
            if (findings.size() < MAX_FINDINGS) {
                findings.add(note.toString());
            }
            entries.put(path, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        if (movedModels > 0) {
            DebugLogger.warning("[PackGuard] " + movedModels + " model(s) carried geometry outside the client's "
                    + (int) WINDOW_MIN + ".." + (int) WINDOW_MAX + "px element window and were re-based;"
                    + " check the authoring scale of those models.");
        }
        if (snappedModels > 0 && findings.size() < MAX_FINDINGS) {
            findings.add(snappedAngles + " element rotation(s) in " + snappedModels + " model(s) were snapped to "
                    + LEGACY_ANGLE_STEP + " degree steps, because clients older than 1.21.6 reject a model with any"
                    + " other angle and draw the missing-model cube (e.g. " + String.join(", ", snappedSamples) + ")");
        }
        return new Report(scanned, movedModels, movedElements, clampedElements, snappedModels, snappedAngles,
                List.copyOf(findings));
    }

    /**
     * Snaps one element's rotation to the nearest {@value #LEGACY_ANGLE_STEP}
     * degree step within +/-{@value #MAX_ANGLE}.
     *
     * @return true when the angle had to change
     */
    private static boolean snapAngle(JsonObject element) {
        if (!element.has("rotation") || !element.get("rotation").isJsonObject()) {
            return false;
        }
        JsonObject rotation = element.getAsJsonObject("rotation");
        if (!rotation.has("angle") || !rotation.get("angle").isJsonPrimitive()) {
            return false;
        }
        float angle;
        try {
            angle = rotation.get("angle").getAsFloat();
        } catch (RuntimeException notANumber) {
            return false;
        }
        float snapped = Math.round(angle / LEGACY_ANGLE_STEP) * LEGACY_ANGLE_STEP;
        snapped = Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, snapped));
        if (Math.abs(snapped - angle) < 1.0e-4f) {
            return false;
        }
        rotation.addProperty("angle", snapped);
        return true;
    }

    /**
     * Translates one element back into the window, then clamps it if its own
     * span is too wide to fit.
     *
     * @return {@code 0} untouched, {@code 1} clamped, {@code 2} translated
     */
    private static int enforceElement(JsonObject element, String path) {
        float[] from = vector(element, "from");
        float[] to = vector(element, "to");
        if (from == null || to == null) {
            return 0;
        }
        float[] shift = new float[3];
        boolean squashed = false;
        for (int axis = 0; axis < 3; axis++) {
            float low = Math.min(from[axis], to[axis]);
            float high = Math.max(from[axis], to[axis]);
            float span = high - low;
            if (span > WINDOW_SPAN) {
                // Cannot be translated: pull the extents themselves inside.
                float center = (low + high) / 2.0f;
                float half = WINDOW_SPAN / 2.0f;
                from[axis] = Math.max(WINDOW_MIN, center - half);
                to[axis] = Math.min(WINDOW_MAX, center + half);
                if (to[axis] - from[axis] <= 0.0f) {
                    from[axis] = WINDOW_MIN;
                    to[axis] = WINDOW_MAX;
                }
                shift[axis] = 0.0f;
                squashed = true;
                continue;
            }
            if (low < WINDOW_MIN) {
                shift[axis] = WINDOW_MIN - low;
            } else if (high > WINDOW_MAX) {
                shift[axis] = WINDOW_MAX - high;
            }
        }
        boolean translated = shift[0] != 0.0f || shift[1] != 0.0f || shift[2] != 0.0f;
        if (translated) {
            for (int axis = 0; axis < 3; axis++) {
                from[axis] += shift[axis];
                to[axis] += shift[axis];
            }
        }
        if (!translated && !squashed) {
            return 0;
        }
        element.add("from", array(from));
        element.add("to", array(to));
        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            JsonObject rotation = element.getAsJsonObject("rotation");
            float[] origin = vector(rotation, "origin");
            if (origin != null) {
                for (int axis = 0; axis < 3; axis++) {
                    origin[axis] += shift[axis];
                }
                rotation.add("origin", array(origin));
            }
        }
        if (squashed) {
            DebugLogger.warning("[PackGuard] Model '" + path + "' has an element spanning more than "
                    + (int) WINDOW_SPAN + "px; slice it into several elements in the source model.");
            return 1;
        }
        return 2;
    }

    private static boolean isModelEntry(String path) {
        return path.startsWith("assets/") && path.contains("/models/") && path.endsWith(".json");
    }

    private static float[] vector(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() < 3) {
            return null;
        }
        try {
            return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static JsonArray array(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(Math.round(value * 1000.0f) / 1000.0f);
        }
        return array;
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping()
            .create();
}
