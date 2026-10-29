package com.mineplus.gun.weapon;

import com.mineplus.infrastructure.content.ContentModel;
import com.mineplus.pack.item.ItemFrames;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Discovers a weapon's animation from the model files that shipped with it.
 *
 * <p>A gun folder is self-describing: every {@code <id>_<pose>.json} inside
 * {@code pack/mineplusgun/models/item/<id>/} is classified by its suffix, and
 * the matching frames become the Core {@link ItemFrames} state space. Nothing
 * is required — a folder with only {@code <id>_idle.json} is a valid weapon
 * that never changes pose, and a folder that adds {@code _loading_N},
 * {@code _fire}, {@code _scope_in} or {@code _equip} gets those animations for
 * free. Adding a gun is therefore "drop a folder in the jar", never a code or
 * frame-spec change.</p>
 *
 * <p>Recognised suffixes (case-insensitive, after the {@code <id>_} prefix):</p>
 * <ul>
 *   <li>rest — {@code idle}, {@code rest}, {@code base}, {@code default}</li>
 *   <li>loaded — {@code primed}, {@code loaded}, {@code ready}, {@code cocked}</li>
 *   <li>reload — {@code loading_N}, {@code reload_N}, {@code load_N}, sorted
 *       numerically and retimed to the weapon's reload duration at runtime</li>
 *   <li>shot — {@code fire_N}, {@code shoot_N}, {@code shot_N}</li>
 *   <li>scope — {@code scope_in}, {@code scope_hold}, {@code scope_out}
 *       (plus the {@code aim_} / {@code zoom_} spellings)</li>
 *   <li>action — {@code cycle}, {@code bolt}, {@code pump}, {@code rack},
 *       {@code rechamber}</li>
 *   <li>equip — {@code equip}, {@code draw}, {@code raise}</li>
 * </ul>
 *
 * <p>A folder with no recognisable rest frame still gets one: the first model
 * is used as the resting pose, because a gun that renders nothing is worse
 * than a gun whose first frame is treated as idle.</p>
 */
public final class WeaponFrames {

    public static final String NAMESPACE = "mineplusgun";

    private static final String MODEL_ROOT = "pack/mineplusgun/models/item/";
    private static final String TEXTURE_ROOT = "pack/mineplusgun/textures/item/";

    private WeaponFrames() {
    }

    /**
     * Builds the frame spec of the weapon whose models live in the Core data
     * folder, or {@code null} when the weapon has no installed models.
     *
     * @param dataFolder the Core's data folder
     * @param id         the weapon id (also its model folder name)
     */
    public static ContentModel discover(File dataFolder, String id) {
        if (dataFolder == null || id == null || id.isBlank()) {
            return null;
        }
        File modelFolder = new File(dataFolder, MODEL_ROOT + id);
        File[] files = modelFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null || files.length == 0) {
            return null;
        }
        List<String> stems = new ArrayList<>(files.length);
        for (File file : files) {
            String name = file.getName();
            stems.add(name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT));
        }
        stems.sort(Comparator.naturalOrder());
        File texture = new File(dataFolder, TEXTURE_ROOT + id + ".png");
        return build(id, stems, texture.isFile() ? id + ".png" : null);
    }

    /**
     * The frame spec of one weapon from its model stems. Exposed separately so
     * an explicit config can reuse the classifier if it wants to.
     */
    public static ContentModel build(String id, List<String> stems, String texture) {
        if (id == null || id.isBlank() || stems == null || stems.isEmpty()) {
            return null;
        }
        List<String> idle = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        List<String> reload = new ArrayList<>();
        List<String> fire = new ArrayList<>();
        List<String> scopeIn = new ArrayList<>();
        List<String> scopeHold = new ArrayList<>();
        List<String> scopeOut = new ArrayList<>();
        List<String> equip = new ArrayList<>();
        List<String> cycle = new ArrayList<>();
        List<String> other = new ArrayList<>();

        for (String stem : stems) {
            String suffix = suffix(stem, id);
            if (matches(suffix, "loading", "reload", "load")) {
                reload.add(stem);
            } else if (matches(suffix, "primed", "loaded", "ready", "cocked")) {
                loaded.add(stem);
            } else if (matches(suffix, "idle", "rest", "base", "default")) {
                idle.add(stem);
            } else if (matches(suffix, "fire", "shoot", "shot")) {
                fire.add(stem);
            } else if (matches(suffix, "scope_in", "scopein", "aim_in", "aimin", "zoom_in")) {
                scopeIn.add(stem);
            } else if (matches(suffix, "scope_hold", "scopehold", "aim_hold", "zoom", "scoped")) {
                scopeHold.add(stem);
            } else if (matches(suffix, "scope_out", "scopeout", "aim_out", "zoom_out")) {
                scopeOut.add(stem);
            } else if (matches(suffix, "equip", "draw", "raise")) {
                equip.add(stem);
            } else if (matches(suffix, "cycle", "bolt", "pump", "rack", "rechamber")) {
                cycle.add(stem);
            } else {
                other.add(stem);
            }
        }

        Comparator<String> sequence = Comparator
                .comparingInt((String stem) -> numericSuffix(stem, id))
                .thenComparing(Comparator.naturalOrder());
        reload.sort(sequence);
        fire.sort(sequence);
        scopeIn.sort(sequence);
        scopeHold.sort(sequence);
        scopeOut.sort(sequence);
        equip.sort(sequence);
        cycle.sort(sequence);

        String idleStem = pickIdle(id, stems, idle, other);
        ItemFrames.Builder builder = ItemFrames.builder(reference(id, idleStem));

        if (!loaded.isEmpty()) {
            builder.loaded(reference(id, loaded.get(0)));
        }
        if (!reload.isEmpty()) {
            builder.reload(references(id, reload));
        }
        if (!fire.isEmpty()) {
            builder.fire(reference(id, fire.get(0)));
            builder.clip("fire", references(id, fire), 0, false);
        }
        addClip(builder, id, "scope_in", scopeIn, false);
        addClip(builder, id, "scope_hold", scopeHold, true);
        addClip(builder, id, "scope_out", scopeOut, false);
        addClip(builder, id, "equip", equip, false);
        addClip(builder, id, "cycle", cycle, false);

        return ContentModel.ofFrames(builder.build(), List.copyOf(stems), texture);
    }

    private static String pickIdle(String id, List<String> stems, List<String> idle, List<String> other) {
        for (String stem : idle) {
            if (suffix(stem, id).equals("idle")) {
                return stem;
            }
        }
        if (!idle.isEmpty()) {
            return idle.get(0);
        }
        for (String stem : stems) {
            if (stem.equals(id) || suffix(stem, id).equals("idle")) {
                return stem;
            }
        }
        if (!other.isEmpty()) {
            return other.get(0);
        }
        return stems.get(0);
    }

    private static void addClip(ItemFrames.Builder builder, String id, String name,
                                List<String> stems, boolean loop) {
        if (!stems.isEmpty()) {
            builder.clip(name, references(id, stems), 0, loop);
        }
    }

    private static List<String> references(String id, List<String> stems) {
        List<String> references = new ArrayList<>(stems.size());
        for (String stem : stems) {
            references.add(reference(id, stem));
        }
        return references;
    }

    private static String reference(String id, String stem) {
        return NAMESPACE + ":item/" + id + "/" + stem.toLowerCase(Locale.ROOT);
    }

    /** The pose part of a stem: {@code rifle_loading_2} -> {@code loading_2}. */
    private static String suffix(String stem, String id) {
        String lower = stem.toLowerCase(Locale.ROOT);
        String prefix = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith(prefix + "_")) {
            return lower.substring(prefix.length() + 1);
        }
        if (lower.startsWith(prefix + "-")) {
            return lower.substring(prefix.length() + 1);
        }
        return lower;
    }

    /** True when the suffix is one of the names, or starts with one + separator. */
    private static boolean matches(String suffix, String... names) {
        for (String name : names) {
            if (suffix.equals(name) || suffix.startsWith(name + "_") || suffix.startsWith(name + "-")) {
                return true;
            }
        }
        return false;
    }

    /** The trailing number of a frame name, or 0 when it has none. */
    private static int numericSuffix(String stem, String id) {
        String suffix = suffix(stem, id);
        int underscore = suffix.lastIndexOf('_');
        if (underscore >= 0 && underscore + 1 < suffix.length()) {
            try {
                return Integer.parseInt(suffix.substring(underscore + 1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
