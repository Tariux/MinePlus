package com.mineplus.gun.weapon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.mob.custom.model.BbModelParser;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.pack.item.BbClipBaker;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Turns a Blockbench project that ships with a gun into that gun's frames.
 *
 * <p>A gun folder may carry one {@code <id>.bbmodel} beside its PNG. That file
 * is the authoring source: its outliner is the rig, and its {@code animations}
 * are the shots the runtime plays. {@link BbClipBaker} (Core) compiles a clip
 * into posed item models; this class decides what those poses are <em>called</em>,
 * because the names are the vocabulary {@link WeaponFrames} classifies and the
 * runtime reads:</p>
 *
 * <ul>
 *   <li>{@code animation.fire} / {@code shoot} / {@code recoil} →
 *       {@code <id>_fire_N}</li>
 *   <li>{@code cycle} / {@code lever} / {@code bolt} / {@code pump} / {@code rack}
 *       / {@code action} / {@code close} / {@code shut} → {@code <id>_cycle_N}.
 *       A break-action file authors its two halves as separate clips
 *       ({@code break_load} for the open, {@code close} for the snap shut); the
 *       open is the reload, the shut is what the runtime plays between shots.</li>
 *   <li>{@code scope} / {@code zoom} / {@code aim} → {@code <id>_scope_in_N},
 *       one {@code <id>_scope_hold} carrying the final pose and
 *       {@code <id>_scope_out_N} walking it back</li>
 *   <li>{@code equip} / {@code draw} / {@code raise} → {@code <id>_equip_N}</li>
 *   <li>{@code reload} / {@code load} / {@code magazine} →
 *       {@code <id>_loading_N}</li>
 *   <li>{@code idle} / {@code breath} / {@code stand} is <b>not</b> baked: a
 *       held item cannot be rewritten every rendered frame, so a breathing loop
 *       has no server-clock representation at all.</li>
 * </ul>
 *
 * <p>Sampling is at the tick rate, capped: 20 poses per authored second, never
 * fewer than three nor more than fourteen per clip, because every frame is a
 * pack asset and one state of the item's {@code custom_model_data} dispatch.
 * Poses that repeat the previous one, or that land exactly back on the rest
 * pose, are dropped, so a clip that holds its end frame for half its length
 * costs one file.</p>
 *
 * <p>Baked files carry a {@code mineplus_baked} marker naming their source clip.
 * That marker does two things: it lets this class sweep frames a changed clip no
 * longer produces, and it is deliberately <em>not</em>
 * {@code mineplus_generated}, so {@link WeaponAnimations} counts a baked pose as
 * authored — a clip-supplied fire, cycle, equip or scope sequence therefore
 * replaces the synthesized fallback instead of colliding with it.</p>
 *
 * <p>Runs after the jar's assets are mirrored and before
 * {@link WeaponAnimations#generateAll}, so a folder with nothing but a
 * {@code .bbmodel} and a PNG installs as a complete, animating weapon.</p>
 */
public final class WeaponBbModels {

    private static final String MODEL_ROOT = "pack/mineplusgun/models/item/";
    private static final String MARKER = "mineplus_baked";

    /**
     * Bumped whenever the way a bake produces a display changes. Frames
     * stamped with an older version are deleted by the sweep before the new
     * pass writes, so a wrong pose from an earlier release can never survive
     * an upgrade — no manual "delete the staged folder" step.
     * Version 7: the derived-display workaround layer is gone. The bake keeps
     * the source's own display block verbatim; sources without one get
     * vanilla's defaults. No geometry facing flip, no pack.held-yaw /
     * held-y-lift / held-yaw-overrides. Re-author the pose in Blockbench
     * (docs/display-slot-mapping.md).
     */
    static final int BAKE_VERSION = 7;
    private static final String VERSION_KEY = "mineplus_bake_version";

    /** Element-name fragments that mark the hand end of a weapon source. */
    private static final String[] GRIP_KEYS = {
        "grip", "handle", "stock", "butt", "comb", "wrist"};

    /** Element-name fragments that mark the muzzle end of a weapon source. */
    private static final String[] MUZZLE_KEYS = {
        "muzzle", "crown", "front_bead", "front_sight", "ejector", "barrel", "underlug"};
    private static final String BBMODEL = ".bbmodel";

    /** Poses sampled per authored second of a clip. */
    private static final int POSES_PER_SECOND = 20;
    private static final int MIN_POSES = 3;
    private static final int MAX_POSES = 14;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * How a gun sits in a hand when the project itself says nothing. A Blockbench
     * gun is authored on the model-space table (a full block is 16 pixels and the
     * origin is the cube's corner), not in a hand, so the poses need a display
     * block to fold onto. These are the values already validated in game for the
     * module's rifles. The held-pose correction from {@code PACK.HELD_YAW} and
     * {@code PACK.HELD_Y_LIFT} composes on top of this table only — a source
     * with an authored display is kept exactly as the author posed it.
     */
    private static final String DEFAULT_DISPLAY = """
            {"thirdperson_righthand":{"rotation":[-4,0,0],"translation":[0,5,-1],"scale":[0.6,0.6,0.6]},
             "thirdperson_lefthand":{"rotation":[-4,0,0],"translation":[0,5,-1],"scale":[0.6,0.6,0.6]},
             "firstperson_righthand":{"rotation":[-12,6,0],"translation":[1,2.5,-6],"scale":[0.62,0.62,0.62]},
             "firstperson_lefthand":{"rotation":[-12,6,0],"translation":[1,2.5,-6],"scale":[0.62,0.62,0.62]},
             "ground":{"rotation":[0,90,0],"translation":[0,2,2],"scale":[0.5,0.5,0.5]},
             "gui":{"rotation":[90,-58,90],"translation":[0,6,0],"scale":[0.55,0.55,0.55]},
             "fixed":{"rotation":[90,45,-90],"translation":[3,5,0],"scale":[0.6,0.6,0.6]}}
            """;

    private WeaponBbModels() {
    }

    /**
     * Bakes every installed gun folder that ships a Blockbench source.
     *
     * @return how many frame files the sources define
     */
    public static int bakeAll(JavaPlugin plugin, PluginContext context) {
        // Held poses are authored: the bake keeps whatever display block the
        // source carries and composes nothing on top of it. The old
        // pack.held-yaw / held-y-lift / held-yaw-overrides keys and the
        // geometry-facing pass are gone — author the pose in Blockbench
        // (see docs/display-slot-mapping.md).
        return bakeTree(plugin.getLogger(), context.moduleSupport().dataFile(MODEL_ROOT));
    }

    /**
     * The same pass over an explicit {@code models/item} root, so the bake is
     * checkable without a server: {@link #bakeAll} only resolves the path.
     */
    public static int bakeTree(Logger log, File root) {
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            return 0;
        }
        Arrays.sort(folders);
        int written = 0;
        int sources = 0;
        for (File folder : folders) {
            if (firstSource(folder) == null) {
                continue;
            }
            String id = folder.getName().toLowerCase(Locale.ROOT);
            try {
                int baked = bake(log, folder);
                if (baked > 0) {
                    sources++;
                }
                written += baked;
            } catch (Exception exception) {
                log.warning("[MineplusGun] Could not bake the Blockbench model of '"
                        + folder.getName() + "': " + exception.getMessage());
            }
        }
        if (written > 0) {
            log.info("[MineplusGun] Baked " + written + " frame(s) from "
                    + sources + " Blockbench source(s).");
        }
        return written;
    }

    // ------------------------------------------------------------------
    // One folder
    // ------------------------------------------------------------------

    private static int bake(Logger log, File folder) throws IOException {
        String id = folder.getName().toLowerCase(Locale.ROOT);
        File source = firstSource(folder);
        byte[] sourceBytes = Files.readAllBytes(source.toPath());
        ModelData model = BbModelParser.parse(id, sourceBytes, reference -> null);
        // The author's display is the final word: whatever display block the
        // source (or a hand-authored frame JSON) carries is baked verbatim;
        // sources without one get vanilla's defaults through the pack. No
        // facing flip, no base yaw, no lift is composed on top.
        AuthoredDisplay display = displayOf(folder, id, sourceBytes);
        BbClipBaker.Style style = new BbClipBaker.Style(display.display(),
                WeaponFrames.NAMESPACE + ":item/" + id, true,
                name -> WeaponFrames.NAMESPACE + ":item/" + id);

        log.info("[MineplusGun] '" + id + "': baking from '" + source.getName()
                + "' with long axis " + longAxis(model) + ", display "
                + (display.authored()
                        ? "authored (kept as-is)"
                        : "not authored (vanilla defaults apply)")
                + ".");
        List<String> issues = new ArrayList<>();
        Set<String> written = new LinkedHashSet<>();
        JsonObject rest = firstOrNull(BbClipBaker.rest(model, style));
        if (rest == null) {
            report(log, id, List.of(source.getName() + ": no cube geometry to bake."));
            return 0;
        }
        File idle = new File(folder, id + "_idle.json");
        if (!idle.isFile() || isBaked(idle)) {
            // No hand-tuned idle: write (or rewrite) ours. A baked idle from an
            // older bake version carries a stale pose and is replaced.
            write(folder, id + "_idle", rest, source.getName(), written);
        } else {
            // The idle already exists and was hand-tuned: keep it, and tell the
            // sweep it is not stale.
            written.add(id + "_idle");
        }

        for (String clip : BbClipBaker.clips(model)) {
            Role role = roleOf(clip);
            if (role == Role.IDLE) {
                continue;
            }
            if (role == Role.UNKNOWN) {
                issues.add("clip '" + clip + "' has no weapon pose to map it to; skipped.");
                continue;
            }
            float seconds = BbClipBaker.lengthSeconds(model, clip);
            int poses = Math.max(MIN_POSES, Math.min(MAX_POSES, Math.round(seconds * POSES_PER_SECOND) + 1));
            BbClipBaker.Result result = BbClipBaker.poses(model, style, clip, poses);
            issues.addAll(result.issues());
            List<JsonObject> frames = distinct(result, rest);
            if (frames.isEmpty()) {
                continue;
            }
            writeRole(folder, id, role, frames, source.getName(), written);
        }

        int removed = sweep(log, folder, id, written);
        if (removed > 0) {
            log.info("[MineplusGun] Removed " + removed
                    + " stale baked frame(s) from '" + id + "'.");
        }
        report(log, id, issues);
        return written.size();
    }

    /** One {@code .bbmodel} per gun folder, in file order. */
    private static File firstSource(File folder) {
        // The shipped defaults keep sources under models/; servers that
        // extracted an older layout keep them at the folder root. Both bake.
        List<File> roots = List.of(folder, new File(folder, "models"));
        List<File> found = new ArrayList<>();
        for (File root : roots) {
            File[] files = root.listFiles((dir, name) ->
                    name.toLowerCase(Locale.ROOT).endsWith(BBMODEL));
            if (files != null) {
                found.addAll(Arrays.asList(files));
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort(Comparator.comparing(File::getName));
        return found.get(0);
    }


    private static void writeRole(File folder, String id, Role role, List<JsonObject> frames,
                                  String source, Set<String> written) throws IOException {
        switch (role) {
            case FIRE -> writeSequence(folder, id + "_fire_", frames, source, written);
            case CYCLE -> writeSequence(folder, id + "_cycle_", frames, source, written);
            case EQUIP -> writeSequence(folder, id + "_equip_", frames, source, written);
            case RELOAD -> {
                // A single reload pose is not a sequence: leaving it unwritten
                // lets WeaponAnimations synthesize its three-pose fallback.
                if (frames.size() >= 2) {
                    writeSequence(folder, id + "_loading_", frames, source, written);
                }
            }
            case SCOPE -> writeScope(folder, id, frames, source, written);
            default -> {
            }
        }
    }

    /**
     * The aim sequence: the poses walked into the scope, one held pose, and the
     * same poses walked back out. The clip's last pose becomes the hold, so
     * aiming down costs one static write while the sights are up, and the out
     * list starts on the frame next to it — reversing the whole list would jump
     * the gun to its lowest aim frame before bringing it back down.
     */
    private static void writeScope(File folder, String id, List<JsonObject> frames,
                                   String source, Set<String> written) throws IOException {
        JsonObject hold = frames.get(frames.size() - 1);
        List<JsonObject> inward = frames.subList(0, frames.size() - 1);
        writeSequence(folder, id + "_scope_in_", inward, source, written);
        write(folder, id + "_scope_hold", hold, source, written);
        List<JsonObject> outward = new ArrayList<>(inward);
        Collections.reverse(outward);
        writeSequence(folder, id + "_scope_out_", outward, source, written);
    }

    private static void writeSequence(File folder, String prefix, List<JsonObject> frames,
                                      String source, Set<String> written) throws IOException {
        for (int index = 0; index < frames.size(); index++) {
            write(folder, prefix + index, frames.get(index), source, written);
        }
    }

    /**
     * The poses that actually move: a frame equal to the one before it, or equal
     * to the rest pose, carries no state.
     */
    private static List<JsonObject> distinct(BbClipBaker.Result result, JsonObject rest) {
        String restKey = GSON.toJson(rest);
        List<JsonObject> out = new ArrayList<>(result.models().size());
        String previous = restKey;
        for (JsonObject pose : result.models()) {
            String key = GSON.toJson(pose);
            if (key.equals(previous)) {
                continue;
            }
            previous = key;
            out.add(pose);
        }
        return out;
    }

    private static void write(File folder, String stem, JsonObject model, String source,
                              Set<String> written) throws IOException {
        JsonObject posed = model.deepCopy();
        posed.addProperty(MARKER, source);
        posed.addProperty(VERSION_KEY, BAKE_VERSION);
        File file = new File(folder, stem + ".json");
        byte[] bytes = GSON.toJson(posed).getBytes(StandardCharsets.UTF_8);
        written.add(stem);
        if (file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) {
            return;
        }
        Files.write(file.toPath(), bytes);
    }

    /**
     * Delete baked frames this source no longer produces, so a renamed clip
     * leaves nothing, and any baked frame stamped by an older {@link #BAKE_VERSION}
     * (an already-rewritten stem included), so a wrong pose from an earlier
     * release cannot survive an upgrade. Hand-authored files are never touched.
     */
    private static int sweep(Logger log, File folder, String id, Set<String> written) {
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            String stem = file.getName().substring(0, file.getName().length() - ".json".length())
                    .toLowerCase(Locale.ROOT);
            if (written.contains(stem) || !isBaked(file)) {
                continue;
            }
            if (bakeVersionOf(file) >= BAKE_VERSION) {
                continue;
            }
            try {
                Files.deleteIfExists(file.toPath());
                removed++;
            } catch (IOException locked) {
                // A stale frame costs one extra pose in the dispatch; it is not
                // worth failing the install over a file the client is holding.
                log.warning("[MineplusGun] Could not remove the stale frame '"
                        + file.getName() + "' of '" + id + "'.");
            }
        }
        return removed;
    }

    private static boolean isBaked(File file) {
        try {
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(file.toPath(), StandardCharsets.UTF_8));
            return parsed.isJsonObject() && parsed.getAsJsonObject().has(MARKER);
        } catch (Exception malformed) {
            return false;
        }
    }

    /** The {@code mineplus_bake_version} stamp of a baked file, or 0 when absent. */
    private static int bakeVersionOf(File file) {
        try {
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(file.toPath(), StandardCharsets.UTF_8));
            JsonElement version = parsed.isJsonObject()
                    ? parsed.getAsJsonObject().get(VERSION_KEY) : null;
            return version != null && version.isJsonPrimitive() ? version.getAsInt() : 0;
        } catch (Exception malformed) {
            return 0;
        }
    }

    private static boolean matches(String name, String[] keys) {
        for (String key : keys) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String longAxis(ModelData model) {
        float x = 0;
        float z = 0;
        for (ModelBone bone : model.bones()) {
            for (ModelCube cube : bone.cubes()) {
                x = Math.max(x, cube.toX() - cube.fromX());
                z = Math.max(z, cube.toZ() - cube.fromZ());
            }
        }
        if (x <= 0 && z <= 0) {
            return "unknown (no cubes)";
        }
        return z >= x ? "Z (barrel forward as authored)" : "X (sideways as authored)";
    }

    // ------------------------------------------------------------------
    // Style and reporting
    // ------------------------------------------------------------------

    /** A display block plus where it came from. */
    private record AuthoredDisplay(JsonObject display, boolean authored) {

        static final AuthoredDisplay DEFAULT = new AuthoredDisplay(
                JsonParser.parseString(DEFAULT_DISPLAY).getAsJsonObject(), false);
    }

    /**
     * The display block the poses fold onto, in priority order: the folder's
     * own hand-authored resting model, the {@code .bbmodel} source's own
     * {@code display} block, then the module's rifle table.
     *
     * <p>Only files without the {@code mineplus_baked} marker are trusted: a
     * frame a previous bake pass wrote already carries that pass's held-pose
     * correction folded into its display, and inheriting it would bake the old
     * correction into every future pose no matter what the config now says.
     * Baked frames are always rebuilt fresh from the source and the current
     * config. Whether the result is <em>authored</em> matters as much as its
     * values: an authored display is the author's final word on how the gun
     * sits in a hand, so the bake composes no automatic correction onto it —
     * only the default table is ever corrected from geometry.</p>
     */
    private static AuthoredDisplay displayOf(File folder, String id, byte[] sourceBytes) {
        for (String stem : new String[]{id + "_primed", id + "_loaded", id + "_idle", id + "_rest"}) {
            File candidate = new File(folder, stem + ".json");
            if (!candidate.isFile()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(
                        Files.readString(candidate.toPath(), StandardCharsets.UTF_8));
                JsonObject root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                if (root == null || root.has(MARKER)) {
                    continue;
                }
                JsonElement display = root.get("display");
                if (display != null && display.isJsonObject()) {
                    return new AuthoredDisplay(display.getAsJsonObject(), true);
                }
            } catch (Exception malformed) {
                // Fall through: a display block the parser cannot read is
                // worse than the one already proven in game.
            }
        }
        // A .bbmodel can carry the author's display adjustments itself
        // (Blockbench's display settings panel writes them on the project
        // root) — same rule: authored, kept as-is.
        try {
            JsonObject root = JsonParser.parseString(
                    new String(sourceBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement display = root.get("display");
            if (display != null && display.isJsonObject()
                    && display.getAsJsonObject().size() > 0) {
                return new AuthoredDisplay(display.getAsJsonObject().deepCopy(), true);
            }
        } catch (Exception malformed) {
            // Fall through to the default table.
        }
        return AuthoredDisplay.DEFAULT;
    }

    private static JsonObject firstOrNull(BbClipBaker.Result result) {
        return result.isEmpty() ? null : result.models().get(0);
    }

    private static void report(Logger log, String id, List<String> issues) {
        for (String issue : issues) {
            log.info("[MineplusGun] '" + id + "': " + issue);
        }
    }

    /** Which weapon pose a clip is authored as. */
    private enum Role {
        FIRE, CYCLE, SCOPE, EQUIP, RELOAD, IDLE, UNKNOWN
    }

    private static Role roleOf(String clip) {
        String name = clip.toLowerCase(Locale.ROOT).replace("animation.", "").replace(":", "_");
        if (contains(name, "idle", "breath", "stand", "rest")) {
            return Role.IDLE;
        }
        if (contains(name, "fire", "shoot", "shot", "recoil")) {
            return Role.FIRE;
        }
        if (contains(name, "scope", "zoom", "aim")) {
            return Role.SCOPE;
        }
        if (contains(name, "cycle", "lever", "bolt", "pump", "rack", "action", "cock", "chamber",
                "close", "shut", "snap", "lock")) {
            return Role.CYCLE;
        }
        if (contains(name, "equip", "draw", "raise")) {
            return Role.EQUIP;
        }
        if (contains(name, "reload", "loading", "load", "magazine", "mag")) {
            return Role.RELOAD;
        }
        return Role.UNKNOWN;
    }

    private static boolean contains(String name, String... tokens) {
        for (String token : tokens) {
            if (name.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
