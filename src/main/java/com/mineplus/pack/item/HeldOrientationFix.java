package com.mineplus.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Normalizes every held-item model into one canonical hand pose.
 *
 * <p>Root cause of the "backward gun" bug: Blockbench authors orient models
 * inconsistently inside their own model space. Two guns that share a
 * byte-identical display block can render facing opposite ways, because the
 * muzzle points toward -Z in one model and toward +Z in the other. No display
 * table can be correct for both conventions at once, and patching individual
 * displays (the previous approach) always misses some contexts or frames.</p>
 *
 * <p>This class therefore does not patch displays. For every model file it:
 * (1) reads the model's own cubes and finds the grip anchor (where the fist
 * meets the weapon) and the muzzle direction; (2) builds the minimal rotation
 * N that maps that muzzle ray onto the canonical -Z axis used by the
 * correctly-authored guns; (3) writes every display context as a pure
 * function of N and the calibrated canonical pose, solving the translation so
 * the grip lands exactly on the hand point (or the bounding-box centre on the
 * gui/ground/fixed target).</p>
 *
 * <p>The canonical constants were calibrated on the pack's known-good pistol
 * model: its authored display is applied unchanged to its own normalized
 * geometry, so every other model inherits exactly the look the pistol already
 * had. Because displays are a pure function of the (display-independent)
 * geometry, running the fix twice produces identical files - the pass is
 * idempotent by construction and cannot double-flip a model.</p>
 */
public final class HeldOrientationFix {

    private HeldOrientationFix() {
    }

    /** Canonical pose of {@code firstperson_righthand}: yaw/pitch/roll degrees. */
    private static final float[] FP_ROT = {-15f, 5f, 0f};
    private static final float[] FP_SCALE = {0.9f, 0.9f, 0.9f};
    /** Where the grip anchor renders in first person (calibrated on pistol). */
    private static final float[] FP_TARGET = {8.418f, 7.715f, 4.039f};

    private static final float[] TP_ROT = {0f, 0f, 0f};
    private static final float[] TP_SCALE = {0.85f, 0.85f, 0.85f};
    /** Where the grip anchor renders in third person (calibrated on pistol). */
    private static final float[] TP_TARGET = {6.8f, 6.375f, 7.325f};

    private static final float[] GUI_ROT = {90f, -58f, 90f};
    private static final float[] GUI_SCALE = {1f, 1f, 1f};
    /** Bounding-box-centre target for the inventory slot. */
    private static final float[] GUI_TARGET = {-9.884f, 3.214f, 8f};
    /** A gui render wider than this is shrunk to fit the slot. */
    private static final float GUI_MAX_SPAN = 14f;

    private static final float[] GROUND_ROT = {0f, 90f, 0f};
    private static final float[] GROUND_SCALE = {0.7f, 0.7f, 0.7f};
    private static final float[] GROUND_TARGET = {5.25f, 4.012f, -4.6f};

    private static final float[] FIXED_ROT = {90f, 45f, -90f};
    private static final float[] FIXED_SCALE = {1f, 1f, 1f};
    private static final float[] FIXED_TARGET = {10.086f, 0.73f, -8.25f};
    /** An item-frame render wider than this is shrunk to fit the frame. */
    private static final float FIXED_MAX_SPAN = 15f;

    /** Cube-name fragments that mark a grip. */
    private static final String[] GRIP_NAMES = {"grip", "wrist", "neck", "handle"};
    /** Cube-name fragments that disqualify a grip candidate (shoulder end, hardware). */
    private static final String[] NOT_GRIP = {"butt", "plate", "toe", "heel", "comb", "cheek",
            "panel", "bolt", "sling", "scope", "swivel", "mag", "cap", "iron"};
    /** Cube-name fragments that mark the muzzle end. */
    private static final String[] MUZZLE_NAMES = {"muzzle", "crown", "bead", "front"};

    /** One display context: rotation, scale, target point, and which anchor to use. */
    private record Pose(float[] rot, float[] scale, float[] target,
                        boolean gripAnchored, float maxSpan) {
    }

    private static final Pose FIRSTPERSON =
            new Pose(FP_ROT, FP_SCALE, FP_TARGET, true, 0f);
    private static final Pose THIRDPERSON =
            new Pose(TP_ROT, TP_SCALE, TP_TARGET, true, 0f);
    private static final Pose GUI =
            new Pose(GUI_ROT, GUI_SCALE, GUI_TARGET, false, GUI_MAX_SPAN);
    private static final Pose GROUND =
            new Pose(GROUND_ROT, GROUND_SCALE, GROUND_TARGET, false, 0f);
    private static final Pose FIXED =
            new Pose(FIXED_ROT, FIXED_SCALE, FIXED_TARGET, false, FIXED_MAX_SPAN);

    /**
     * Walks every {@code *_idle.json} under {@code root} (one folder per item),
     * computes the canonical displays from the idle's geometry and writes them
     * into every model in the same folder (idle and animation frames alike).
     *
     * @return how many files were rewritten
     */
    public static int fixAll(Logger log, File root) {
        if (root == null || !root.isDirectory()) {
            return 0;
        }
        int changed = 0;
        try (Stream<Path> folders = Files.walk(root.toPath(), 2)) {
            for (Path dir : folders.filter(Files::isDirectory).toList()) {
                File idle = null;
                File[] jsons = dir.toFile().listFiles((d, n) -> n.endsWith(".json"));
                if (jsons == null) {
                    continue;
                }
                for (File f : jsons) {
                    if (f.getName().endsWith("_idle.json")) {
                        idle = f;
                        break;
                    }
                }
                if (idle != null) {
                    changed += fixFolder(idle, dir.toFile());
                }
            }
        } catch (IOException failure) {
            log.warning("[HeldOrientationFix] Could not walk " + root + ": " + failure.getMessage());
            return changed;
        }
        if (changed > 0) {
            log.info("[HeldOrientationFix] Re-posed " + changed
                    + " held-item model(s) onto the canonical hand pose.");
        }
        return changed;
    }

    /** Applies the idle's canonical displays to every model in the folder. */
    private static int fixFolder(File idle, File dir) {
        JsonObject base;
        try {
            base = read(idle);
        } catch (Exception unreadable) {
            return 0;
        }
        float[][][] idleSolution = solve(base);
        int changed = 0;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            try {
                boolean isIdle = f.equals(idle);
                JsonObject model = isIdle ? base : read(f);
                float[][][] solution = isIdle ? idleSolution : solve(model);
                if (solution == null) {
                    continue; // no geometry: leave untouched
                }
                if (writeDisplays(model, solution)) {
                    Files.writeString(f.toPath(), GSON.toJson(model), StandardCharsets.UTF_8);
                    changed++;
                }
            } catch (Exception malformed) {
                // one malformed file must not stop the pass
            }
        }
        return changed;
    }

    /**
     * Solves the five display matrices for one model. Returns
     * {@code {{rot},{scale},{translation}} x 5} for
     * {@code [fp, tp, gui, ground, fixed]}, or null when the model has no cubes.
     */
    public static float[][][] solve(JsonObject model) {
        if (model == null || !model.has("elements") || !model.get("elements").isJsonArray()) {
            return null;
        }
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        float[] grip = gripAnchor(elements);
        float[] dir = muzzleDirection(elements, grip);
        float[][] normal = normalizeRotation(dir);
        float[] boxLow = new float[3];
        float[] boxHigh = new float[3];
        bounds(elements, boxLow, boxHigh);
        float[] centre = new float[3];
        for (int i = 0; i < 3; i++) {
            centre[i] = (boxLow[i] + boxHigh[i]) / 2f;
        }
        float maxSpan = 0f;
        for (int i = 0; i < 3; i++) {
            maxSpan = Math.max(maxSpan, boxHigh[i] - boxLow[i]);
        }

        Pose[] poses = {FIRSTPERSON, THIRDPERSON, GUI, GROUND, FIXED};
        float[][][] out = new float[5][3][3];
        for (int p = 0; p < poses.length; p++) {
            Pose pose = poses[p];
            float shrink = 1f;
            if (pose.maxSpan() > 0f && maxSpan > pose.maxSpan()) {
                shrink = pose.maxSpan() / maxSpan;
            }
            // The game applies R(json) * diag(scale) to the raw model, so the
            // written rotation must be the combined R(rot) * N - otherwise the
            // normalization never reaches the client.
            float[][] jsonRotation = multiply(euler(pose.rot()), normal);
            float[][] M = multiply(jsonRotation, diag(scale(pose.scale(), shrink)));
            float[] anchor = pose.gripAnchored() ? grip : centre;
            float[] rendered = apply(M, anchor);
            float[] translation = new float[3];
            for (int i = 0; i < 3; i++) {
                translation[i] = round(pose.target()[i] - rendered[i]);
            }
            out[p][0] = eulerDegrees(jsonRotation);
            out[p][1] = scale(pose.scale(), shrink);
            out[p][2] = translation;
        }
        return out;
    }

    /** Writes the solved displays into the model. Returns true when something changed. */
    private static boolean writeDisplays(JsonObject model, float[][][] solution) {
        JsonObject display = model.has("display") && model.get("display").isJsonObject()
                ? model.getAsJsonObject("display") : new JsonObject();
        // remove contexts we no longer solve so stale values cannot survive
        for (String stale : new String[]{"firstperson_lefthand", "thirdperson_lefthand", "head"}) {
            display.remove(stale);
        }
        model.add("display", display);
        String[] contexts = {"firstperson_righthand", "thirdperson_righthand",
                "gui", "ground", "fixed"};
        boolean changed = false;
        for (int p = 0; p < contexts.length; p++) {
            JsonObject written = new JsonObject();
            JsonArray rot = new JsonArray();
            JsonArray scale = new JsonArray();
            JsonArray trans = new JsonArray();
            for (int i = 0; i < 3; i++) {
                rot.add(round(solution[p][0][i]));
                scale.add(round(solution[p][1][i]));
                trans.add(round(solution[p][2][i]));
            }
            boolean identityRot = isIdentity(rot, 0f);
            boolean identityScale = isIdentity(scale, 1f);
            if (!identityRot) {
                written.add("rotation", rot);
            }
            written.add("translation", trans);
            if (!identityScale) {
                written.add("scale", scale);
            }
            JsonObject previous = display.has(contexts[p]) && display.get(contexts[p]).isJsonObject()
                    ? display.getAsJsonObject(contexts[p]) : null;
            if (!samePose(previous, written)) {
                display.add(contexts[p], written);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean samePose(JsonObject previous, JsonObject written) {
        if (previous == null || previous.keySet().size() != written.keySet().size()) {
            return false;
        }
        for (String key : written.keySet()) {
            if (!previous.has(key)) {
                return false;
            }
            JsonArray a = previous.getAsJsonArray(key);
            JsonArray b = written.getAsJsonArray(key);
            for (int i = 0; i < 3; i++) {
                if (Math.abs(a.get(i).getAsFloat() - b.get(i).getAsFloat()) > 0.001f) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIdentity(JsonArray three, float value) {
        for (int i = 0; i < 3; i++) {
            if (three.get(i).getAsFloat() != value) {
                return false;
            }
        }
        return true;
    }

    /** The cube where the fist meets the weapon. */
    static float[] gripAnchor(JsonArray elements) {
        List<float[]> gripCubes = new ArrayList<>();
        List<float[]> triggerCubes = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject e = elements.get(i).getAsJsonObject();
            if (!e.has("from") || !e.has("to")) {
                continue;
            }
            String name = e.has("name") ? e.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
            if (name.isEmpty()) {
                continue;
            }
            float[] c = centre(e);
            if (name.contains("trigger")) {
                triggerCubes.add(c);
            }
            for (String grip : GRIP_NAMES) {
                if (name.contains(grip)) {
                    boolean excluded = false;
                    for (String bad : NOT_GRIP) {
                        if (name.contains(bad)) {
                            excluded = true;
                            break;
                        }
                    }
                    if (!excluded) {
                        gripCubes.add(c);
                    }
                    break;
                }
            }
        }
        if (!gripCubes.isEmpty() && !triggerCubes.isEmpty()) {
            float[] trigger = triggerCubes.get(0);
            float[] best = gripCubes.get(0);
            double bestDist = dist(best, trigger);
            for (float[] c : gripCubes) {
                double d = dist(c, trigger);
                if (d < bestDist) {
                    best = c;
                    bestDist = d;
                }
            }
            return best;
        }
        if (!gripCubes.isEmpty()) {
            return gripCubes.get(0);
        }
        if (!triggerCubes.isEmpty()) {
            return triggerCubes.get(0);
        }
        // no named cubes: the lowest cube is where the hand meets the weapon
        float[] lowest = null;
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject e = elements.get(i).getAsJsonObject();
            if (!e.has("from") || !e.has("to")) {
                continue;
            }
            float[] c = centre(e);
            if (lowest == null || c[1] < lowest[1]) {
                lowest = c;
            }
        }
        return lowest != null ? lowest : new float[]{0f, 0f, 0f};
    }

    /** Unit vector from the grip toward the muzzle. */
    static float[] muzzleDirection(JsonArray elements, float[] grip) {
        float[] best = null;
        double bestDist = -1d;
        boolean named = false;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int i = 0; i < elements.size(); i++) {
                if (!elements.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject e = elements.get(i).getAsJsonObject();
                if (!e.has("from") || !e.has("to")) {
                    continue;
                }
                if (pass == 0) {
                    String name = e.has("name")
                            ? e.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
                    boolean isMuzzle = false;
                    for (String m : MUZZLE_NAMES) {
                        if (name.contains(m)) {
                            isMuzzle = true;
                            break;
                        }
                    }
                    if (!isMuzzle) {
                        continue;
                    }
                }
                named = true;
                float[] c = centre(e);
                double d = dist(c, grip);
                if (d > bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        if (!named || best == null) {
            return new float[]{0f, 0f, -1f};
        }
        float[] d = new float[]{best[0] - grip[0], best[1] - grip[1], best[2] - grip[2]};
        float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len < 1e-3f) {
            return new float[]{0f, 0f, -1f};
        }
        return new float[]{d[0] / len, d[1] / len, d[2] / len};
    }

    /** Minimal rotation mapping the muzzle direction onto canonical -Z. */
    static float[][] normalizeRotation(float[] dir) {
        float[] target = {0f, 0f, -1f};
        float dot = clamp(dir[0] * target[0] + dir[1] * target[1] + dir[2] * target[2], -1f, 1f);
        float[] axis = cross(dir, target);
        float sin = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (sin < 1e-6f) {
            // dir already on the canonical axis; a 180-degree yaw maps +Z to -Z
            return dot > 0f ? identity() : diag(new float[]{-1f, 1f, -1f});
        }
        float angle = (float) Math.atan2(sin, dot);
        axis[0] /= sin;
        axis[1] /= sin;
        axis[2] /= sin;
        return axisAngle(axis, angle);
    }

    // ---------- small matrix / vector helpers ----------

    private static float[][] euler(float[] deg) {
        float rx = (float) Math.toRadians(deg[0]);
        float ry = (float) Math.toRadians(deg[1]);
        float rz = (float) Math.toRadians(deg[2]);
        float[][] X = {{1, 0, 0}, {0, (float) Math.cos(rx), -(float) Math.sin(rx)},
                {0, (float) Math.sin(rx), (float) Math.cos(rx)}};
        float[][] Y = {{(float) Math.cos(ry), 0, (float) Math.sin(ry)}, {0, 1, 0},
                {-(float) Math.sin(ry), 0, (float) Math.cos(ry)}};
        float[][] Z = {{(float) Math.cos(rz), -(float) Math.sin(rz), 0},
                {(float) Math.sin(rz), (float) Math.cos(rz), 0}, {0, 0, 1}};
        return multiply(multiply(X, Y), Z);
    }

    /** Euler XYZ degrees whose matrix is {@code Rx(a) Ry(b) Rz(c) = m}. */
    static float[] eulerDegrees(float[][] m) {
        float b = (float) Math.asin(clamp(m[0][2], -1f, 1f));
        float a;
        float c;
        if (Math.abs(m[0][2]) < 0.9999f) {
            a = (float) Math.atan2(-m[1][2], m[2][2]);
            c = (float) Math.atan2(-m[0][1], m[0][0]);
        } else {
            a = (float) Math.atan2(m[1][0], m[1][1]);
            c = 0f;
        }
        return new float[]{round((float) Math.toDegrees(a)),
                round((float) Math.toDegrees(b)), round((float) Math.toDegrees(c))};
    }

    private static float[][] axisAngle(float[] axis, float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float t = 1f - c;
        float x = axis[0];
        float y = axis[1];
        float z = axis[2];
        return new float[][]{
                {t * x * x + c, t * x * y - s * z, t * x * z + s * y},
                {t * x * y + s * z, t * y * y + c, t * y * z - s * x},
                {t * x * z - s * y, t * y * z + s * x, t * z * z + c}};
    }

    private static float[][] multiply(float[][] a, float[][] b) {
        float[][] out = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += a[i][k] * b[k][j];
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    private static float[] apply(float[][] m, float[] v) {
        return new float[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static float[][] diag(float[] s) {
        return new float[][]{{s[0], 0, 0}, {0, s[1], 0}, {0, 0, s[2]}};
    }

    private static float[][] identity() {
        return diag(new float[]{1f, 1f, 1f});
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] scale(float[] base, float factor) {
        return new float[]{base[0] * factor, base[1] * factor, base[2] * factor};
    }

    private static float[] centre(JsonObject element) {
        float[] from = floats(element.getAsJsonArray("from"));
        float[] to = floats(element.getAsJsonArray("to"));
        return new float[]{(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
    }

    private static void bounds(JsonArray elements, float[] low, float[] high) {
        Arrays.fill(low, Float.MAX_VALUE);
        Arrays.fill(high, -Float.MAX_VALUE);
        boolean any = false;
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isJsonObject()) {
                continue;
            }
            JsonObject e = elements.get(i).getAsJsonObject();
            if (!e.has("from") || !e.has("to")) {
                continue;
            }
            float[] from = floats(e.getAsJsonArray("from"));
            float[] to = floats(e.getAsJsonArray("to"));
            any = true;
            for (int a = 0; a < 3; a++) {
                low[a] = Math.min(low[a], Math.min(from[a], to[a]));
                high[a] = Math.max(high[a], Math.max(from[a], to[a]));
            }
        }
        if (!any) {
            Arrays.fill(low, 0f);
            Arrays.fill(high, 0f);
        }
    }

    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    private static double dist(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float clamp(float v, float low, float high) {
        return v < low ? low : Math.min(v, high);
    }

    private static float round(float v) {
        return Math.round(v * 1000f) / 1000f;
    }

    private static JsonObject read(File f) throws IOException {
        return JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
}
