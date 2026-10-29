package com.mineplus.gun.weapon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mineplus.infrastructure.PluginContext;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prepares a gun folder's animation at install time.
 *
 * <p>The shipped gun packs are authored as a handful of keyframes — a pistol
 * reload is five models over a 36-tick reload, so a server-driven frame write
 * would move the gun in ~7-tick steps. This class densifies what the pack
 * already expresses instead of asking an artist for forty files:</p>
 * <ul>
 *   <li><b>reload</b> — every gap between two authored keyframes is filled
 *       with interpolated frames ({@code loading_0_t1..tN}), interpolating the
 *       item's own {@code display} transforms and its element geometry, so the
 *       hand path and any moving bolt/magazine keep their authored motion;</li>
 *   <li><b>fire</b> — a gun whose pack has no shot pose gets a two-frame
 *       recoil generated from its loaded pose (kick back and up, then settle
 *       into the resting pose the classifier appends);</li>
 *   <li><b>equip</b> — a gun whose pack has no draw pose gets a two-frame
 *       raise generated from its idle pose (down and up);</li>
 *   <li><b>cycle</b> — a gun whose pack has no action frames (a western lever,
 *       pump or bolt) gets a four-frame "work the action" motion generated from
 *       its loaded pose, timed by the weapon's own {@code cycle-ticks} so fire →
 *       cycle → ready reads as the gun being operated;</li>
 *   <li><b>scope</b> — a gun whose pack has no aim poses gets a held "down the
 *       sights" frame plus in/out approaches, so a scoped weapon actually shows
 *       a different pose while aiming.</li>
 * </ul>
 *
 * <p>Generated files carry a {@code mineplus_generated} marker. They are
 * excluded from the next run's keyframe set, so densification is idempotent,
 * and a file this run did not produce is deleted (a keyframe removed from the
 * jar never leaves a stale tween behind).</p>
 *
 * <p>Every pose in these packs lives in the item's {@code display} block, so
 * interpolation there is faithful; element interpolation is only applied when
 * the two keyframes have the same element structure, and otherwise the
 * earlier frame's geometry is kept.</p>
 */
public final class WeaponAnimations {

    private static final String MODEL_ROOT = "pack/mineplusgun/models/item/";
    /** Frames one densified sequence aims for (≈22 distinct poses per second at a 2 s reload). */
    private static final int TARGET_FRAMES = 40;
    private static final int MAX_TWEENS_PER_GAP = 12;
    private static final String MARKER = "mineplus_generated";
    private static final String[] FIRST_PERSON = {"firstperson_righthand", "firstperson_lefthand"};
    private static final String[] THIRD_PERSON = {"thirdperson_righthand", "thirdperson_lefthand"};

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private WeaponAnimations() {
    }

    /**
     * Densifies and completes the animation of every installed gun folder.
     *
     * @return how many generated files were written or changed
     */
    public static int generateAll(JavaPlugin plugin, PluginContext context) {
        File root = context.moduleSupport().dataFile(MODEL_ROOT);
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            return 0;
        }
        int written = 0;
        for (File folder : folders) {
            try {
                written += generate(folder);
            } catch (Exception exception) {
                plugin.getLogger().warning("[MineplusGun] Could not prepare the animation of '"
                        + folder.getName() + "': " + exception.getMessage());
            }
        }
        if (written > 0) {
            plugin.getLogger().info("[MineplusGun] Prepared " + written
                    + " animation frame(s) across " + folders.length + " gun folder(s).");
        }
        return written;
    }

    private static int generate(File folder) throws IOException {
        String id = folder.getName().toLowerCase(Locale.ROOT);
        Map<String, JsonObject> models = readModels(folder);
        if (models.isEmpty()) {
            return 0;
        }

        List<String> authored = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : models.entrySet()) {
            if (!entry.getValue().has(MARKER)) {
                authored.add(entry.getKey());
            }
        }
        if (authored.isEmpty()) {
            authored.addAll(models.keySet());
        }
        WeaponFrames.Layout layout = WeaponFrames.layout(id, authored);

        Map<String, JsonObject> generated = new LinkedHashMap<>();
        addReloadTweens(generated, models, layout.reloadStems());

        JsonObject idle = models.get(layout.idleStem());
        JsonObject loaded = layout.loadedStem() == null ? null : models.get(layout.loadedStem());

        if (!layout.hasFire()) {
            JsonObject base = loaded != null ? loaded : idle;
            if (base != null) {
                generated.put(id + "_fire_0", kick(base, 1.0));
                generated.put(id + "_fire_1", kick(base, 0.42));
            }
        }
        if (!layout.hasEquip() && idle != null) {
            generated.put(id + "_equip_0", lower(idle, 1.0));
            generated.put(id + "_equip_1", lower(idle, 0.45));
        }
        if (!layout.hasCycle()) {
            JsonObject base = loaded != null ? loaded : idle;
            if (base != null) {
                // Working the action: the hand pulls the gun down and rolls it
                // outward, then brings it back up into the loaded pose.
                double[] shape = {0.55, 1.0, 0.85, 0.45};
                for (int step = 0; step < shape.length; step++) {
                    generated.put(id + "_cycle_" + step, workAction(base, shape[step]));
                }
            }
        }
        if (layout.scopeInStems().isEmpty() && layout.scopeHoldStems().isEmpty()
                && layout.scopeOutStems().isEmpty()) {
            JsonObject base = loaded != null ? loaded : idle;
            if (base != null) {
                // Aiming down the sights: the gun comes up and in, holds, and
                // returns. Generated for every gun; only a weapon configured
                // with `aim.scope: true` ever plays it.
                generated.put(id + "_scope_hold", aim(base, 1.0));
                generated.put(id + "_scope_in_0", aim(base, 0.45));
                generated.put(id + "_scope_in_1", aim(base, 0.8));
                generated.put(id + "_scope_out_0", aim(base, 0.7));
                generated.put(id + "_scope_out_1", aim(base, 0.35));
            }
        }

        int written = 0;
        for (Map.Entry<String, JsonObject> entry : generated.entrySet()) {
            File file = new File(folder, entry.getKey() + ".json");
            byte[] bytes = GSON.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8);
            if (writeIfChanged(file, bytes)) {
                written++;
            }
        }
        for (Map.Entry<String, JsonObject> entry : models.entrySet()) {
            if (!entry.getValue().has(MARKER) || generated.containsKey(entry.getKey())) {
                continue;
            }
            File stale = new File(folder, entry.getKey() + ".json");
            if (stale.isFile()) {
                Files.deleteIfExists(stale.toPath());
            }
        }
        return written;
    }

    private static void addReloadTweens(Map<String, JsonObject> generated,
                                        Map<String, JsonObject> models, List<String> reload) {
        if (reload.size() < 2) {
            return;
        }
        int tweens = tweensPerGap(reload.size());
        for (int index = 0; index < reload.size() - 1; index++) {
            JsonObject from = models.get(reload.get(index));
            JsonObject to = models.get(reload.get(index + 1));
            if (from == null || to == null) {
                continue;
            }
            for (int step = 1; step <= tweens; step++) {
                double progress = step / (double) (tweens + 1);
                generated.put(reload.get(index) + "_t" + step, tween(from, to, progress));
            }
        }
    }

    /** Interpolated frames per authored gap, so the sequence lands near {@link #TARGET_FRAMES}. */
    private static int tweensPerGap(int keyframes) {
        int gaps = keyframes - 1;
        double raw = (TARGET_FRAMES - keyframes) / (double) gaps;
        return Math.max(1, Math.min(MAX_TWEENS_PER_GAP, (int) Math.round(raw)));
    }

    // ------------------------------------------------------------------
    // Generated frames
    // ------------------------------------------------------------------

    private static JsonObject tween(JsonObject from, JsonObject to, double progress) {
        JsonObject out = from.deepCopy();
        out.addProperty(MARKER, "tween");
        lerpDisplay(from, to, progress, out);
        lerpElements(from, to, progress, out);
        return out;
    }

    /**
     * A recoil pose derived from the loaded pose: the whole gun kicks back
     * toward the player and the muzzle rises, then the classifier's appended
     * resting frame settles it.
     */
    private static JsonObject kick(JsonObject base, double amount) {
        JsonObject out = base.deepCopy();
        out.addProperty(MARKER, "fire");
        JsonObject display = object(out, "display");
        if (display == null) {
            return out;
        }
        for (String slot : FIRST_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, -0.9 * amount);
            nudge(transform, "translation", 2, 1.5 * amount);
            nudge(transform, "rotation", 0, -7.0 * amount);
            nudge(transform, "rotation", 2, 2.5 * amount);
        }
        for (String slot : THIRD_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, -0.5 * amount);
            nudge(transform, "translation", 2, 0.8 * amount);
            nudge(transform, "rotation", 0, -4.0 * amount);
        }
        return out;
    }

    /** A lowered draw pose derived from the idle pose: the hand brings the gun up. */
    private static JsonObject lower(JsonObject base, double amount) {        JsonObject out = base.deepCopy();
        out.addProperty(MARKER, "equip");
        JsonObject display = object(out, "display");
        if (display == null) {
            return out;
        }
        for (String slot : FIRST_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, 6.0 * amount);
            nudge(transform, "translation", 2, -1.5 * amount);
            nudge(transform, "rotation", 0, 30.0 * amount);
            nudge(transform, "rotation", 2, 8.0 * amount);
            nudge(transform, "scale", 0, -0.08 * amount);
            nudge(transform, "scale", 1, -0.08 * amount);
            nudge(transform, "scale", 2, -0.08 * amount);
        }
        for (String slot : THIRD_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, 3.0 * amount);
            nudge(transform, "rotation", 0, 15.0 * amount);
        }
        return out;
    }

    /**
     * An aiming pose derived from the loaded pose: the gun rises toward the
     * centre of the view and draws back toward the shoulder, so the sights line
     * up with the crosshair instead of the muzzle sitting low-right.
     */
    private static JsonObject aim(JsonObject base, double amount) {
        JsonObject out = base.deepCopy();
        out.addProperty(MARKER, "scope");
        JsonObject display = object(out, "display");
        if (display == null) {
            return out;
        }
        for (String slot : FIRST_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 0, -0.9 * amount);
            nudge(transform, "translation", 1, 1.3 * amount);
            nudge(transform, "translation", 2, -1.4 * amount);
            nudge(transform, "rotation", 0, -2.5 * amount);
            nudge(transform, "rotation", 2, -3.0 * amount);
        }
        for (String slot : THIRD_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, 0.6 * amount);
            nudge(transform, "translation", 2, -0.6 * amount);
        }
        return out;
    }

    /**
     * A "working the action" pose derived from the loaded pose: the gun drops
     * and rolls outward as the lever, pump or bolt is run, then the sequence
     * returns to the loaded pose. Timed by the weapon's own cycle ticks, so a
     * pump shotgun and a bolt rifle both read as their own action.
     */
    private static JsonObject workAction(JsonObject base, double amount) {
        JsonObject out = base.deepCopy();
        out.addProperty(MARKER, "cycle");
        JsonObject display = object(out, "display");
        if (display == null) {
            return out;
        }
        for (String slot : FIRST_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, -2.2 * amount);
            nudge(transform, "translation", 2, 0.9 * amount);
            nudge(transform, "rotation", 0, 6.0 * amount);
            nudge(transform, "rotation", 2, -16.0 * amount);
        }
        for (String slot : THIRD_PERSON) {
            JsonObject transform = object(display, slot);
            if (transform == null) {
                continue;
            }
            nudge(transform, "translation", 1, -1.2 * amount);
            nudge(transform, "rotation", 2, -8.0 * amount);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Interpolation
    // ------------------------------------------------------------------

    private static void lerpDisplay(JsonObject from, JsonObject to, double progress, JsonObject out) {
        JsonObject left = object(from, "display");
        JsonObject right = object(to, "display");
        if (left == null && right == null) {
            return;
        }
        JsonObject merged = new JsonObject();
        Set<String> slots = new LinkedHashSet<>();
        if (left != null) {
            slots.addAll(left.keySet());
        }
        if (right != null) {
            slots.addAll(right.keySet());
        }
        for (String slot : slots) {
            JsonObject a = left == null ? null : object(left, slot);
            JsonObject b = right == null ? null : object(right, slot);
            if (a == null && b == null) {
                continue;
            }
            if (a == null) {
                merged.add(slot, b.deepCopy());
            } else if (b == null) {
                merged.add(slot, a.deepCopy());
            } else {
                JsonObject transform = a.deepCopy();
                lerpArray(a, b, "translation", progress, transform, false);
                lerpArray(a, b, "scale", progress, transform, false);
                lerpArray(a, b, "rotation", progress, transform, true);
                merged.add(slot, transform);
            }
        }
        out.add("display", merged);
    }

    private static void lerpElements(JsonObject from, JsonObject to, double progress, JsonObject out) {
        JsonArray left = array(from, "elements");
        JsonArray right = array(to, "elements");
        if (left == null || right == null || left.size() != right.size()) {
            return;
        }
        JsonArray merged = new JsonArray(left.size());
        for (int index = 0; index < left.size(); index++) {
            JsonObject a = element(left.get(index));
            JsonObject b = element(right.get(index));
            if (a == null || b == null || !sameStructure(a, b)) {
                merged.add(left.get(index).deepCopy());
                continue;
            }
            JsonObject element = a.deepCopy();
            lerpArray(a, b, "from", progress, element, false);
            lerpArray(a, b, "to", progress, element, false);
            JsonObject rotation = object(a, "rotation");
            JsonObject otherRotation = object(b, "rotation");
            if (rotation != null && otherRotation != null
                    && rotation.has("angle") && otherRotation.has("angle")) {
                JsonObject lerped = rotation.deepCopy();
                lerped.addProperty("angle", round(lerpAngle(
                        rotation.get("angle").getAsDouble(),
                        otherRotation.get("angle").getAsDouble(), progress)));
                element.add("rotation", lerped);
            }
            merged.add(element);
        }
        out.add("elements", merged);
    }

    /** Two elements can be interpolated only when they describe the same faces. */
    private static boolean sameStructure(JsonObject a, JsonObject b) {
        JsonObject left = object(a, "faces");
        JsonObject right = object(b, "faces");
        return left != null && right != null && left.keySet().equals(right.keySet());
    }

    private static void lerpArray(JsonObject from, JsonObject to, String key, double progress,
                                  JsonObject out, boolean angle) {
        JsonArray a = array(from, key);
        JsonArray b = array(to, key);
        if (a == null || b == null || a.size() != b.size()) {
            return;
        }
        JsonArray result = new JsonArray(a.size());
        for (int index = 0; index < a.size(); index++) {
            double start = a.get(index).getAsDouble();
            double end = b.get(index).getAsDouble();
            double value = angle ? lerpAngle(start, end, progress) : start + (end - start) * progress;
            result.add(new JsonPrimitive(round(value)));
        }
        out.add(key, result);
    }

    /** Shortest-arc angle interpolation, so a 179° pose change never spins the long way. */
    private static double lerpAngle(double from, double to, double progress) {
        double delta = (to - from) % 360.0;
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return from + delta * progress;
    }

    private static void nudge(JsonObject transform, String key, int index, double delta) {
        JsonArray values = array(transform, key);
        if (values == null || values.size() <= index) {
            return;
        }
        values.set(index, new JsonPrimitive(round(values.get(index).getAsDouble() + delta)));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private static Map<String, JsonObject> readModels(File folder) {
        Map<String, JsonObject> models = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return models;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            String stem = name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT);
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(
                        Files.readString(file.toPath(), StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    models.put(stem, parsed.getAsJsonObject());
                }
            } catch (Exception malformed) {
                // A model the pack itself cannot read is not ours to repair;
                // leaving it out keeps generation honest about its keyframes.
            }
        }
        return models;
    }

    private static boolean writeIfChanged(File file, byte[] bytes) throws IOException {
        if (file.isFile()) {
            byte[] existing = Files.readAllBytes(file.toPath());
            if (Arrays.equals(existing, bytes)) {
                return false;
            }
        }
        Files.write(file.toPath(), bytes);
        return true;
    }

    private static JsonObject element(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }
}
