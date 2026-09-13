package com.mineplus.pack.block;

import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Instrument;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.block.data.type.NoteBlock;

/**
 * A pool of vanilla block states a pack block can be displayed through.
 *
 * <p>A {@code BlockDisplay} renders the model of the block state it carries,
 * so a custom block model is delivered by borrowing one state of a vanilla
 * carrier block and redirecting <em>that state only</em> to the generated
 * model. The carrier must render with the client's {@code MODEL} render type —
 * blocks whose render type is {@code INVISIBLE} (light, structure_void,
 * barrier) draw nothing no matter what their blockstate model says, so they can
 * never carry a display.</p>
 *
 * <p>{@link #NOTE_BLOCK} is the shipped carrier. Every note-block state renders
 * the same {@code minecraft:block/note_block} model, so the host file can
 * reproduce the complete vanilla table and redirect only the allocated states.
 * Allocation draws from the rare mob-head instruments (zombie, skeleton,
 * creeper, dragon, wither_skeleton, piglin, custom_head) at {@code note 0..24}
 * and {@code powered=false}, so a naturally placed note block almost never
 * lands on an allocated state; when one does — or when a player has not applied
 * the pack — it renders a normal note block rather than nothing.</p>
 *
 * <p>The state table is enumerated at runtime from the server's own block data
 * ({@link #warmUp()} on the main thread), so the generated host file always
 * matches the running version's exact property names and values.</p>
 */
public enum PackBlockCarrier {

    /** Vanilla note blocks; uniform model, {@code MODEL} render type. */
    NOTE_BLOCK("minecraft:note_block", "minecraft:block/note_block");

    /** Instruments that require a mob head on the note block — rare in normal play. */
    private static final Set<String> RARE_INSTRUMENTS = Set.of(
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head");

    private static final int MAX_NOTE = 24;
    private static final Object WARM_LOCK = new Object();
    private static final Map<PackBlockCarrier, StateTable> TABLES = new ConcurrentHashMap<>();
    private static volatile boolean warmed;

    private final String materialId;
    private final String vanillaModel;

    PackBlockCarrier(String materialId, String vanillaModel) {
        this.materialId = materialId;
        this.vanillaModel = vanillaModel;
    }

    /**
     * Enumerates every carrier's vanilla state table from the live registry.
     * Runs on the main thread (Bukkit block-data API); the async compiler then
     * only reads the cached strings.
     */
    public static void warmUp() {
        if (warmed) {
            return;
        }
        synchronized (WARM_LOCK) {
            if (warmed) {
                return;
            }
            TABLES.put(NOTE_BLOCK, buildNoteBlockTable());
            warmed = true;
        }
    }

    private static StateTable buildNoteBlockTable() {
        List<String> all = new ArrayList<>();
        List<String> allocatable = new ArrayList<>();
        Material material = Material.matchMaterial(NOTE_BLOCK.materialId);
        if (material == null || !(material.createBlockData() instanceof NoteBlock data)) {
            DebugLogger.warning("[Pack] Note-block carrier unavailable on this server; pack blocks are disabled.");
            return new StateTable(null, List.of(), List.of());
        }
        for (Instrument instrument : Instrument.values()) {
            try {
                data.setInstrument(instrument);
            } catch (RuntimeException unsupported) {
                // API/server instrument mismatch: skip rather than abort the table.
                continue;
            }
            for (int note = 0; note <= MAX_NOTE; note++) {
                data.setNote(new Note(note));
                for (boolean powered : new boolean[]{false, true}) {
                    data.setPowered(powered);
                    String key = stateKey(data.getAsString());
                    if (key.isBlank()) {
                        continue;
                    }
                    all.add(key);
                    if (!powered && isRareInstrument(key)) {
                        allocatable.add(key);
                    }
                }
            }
        }
        if (allocatable.isEmpty()) {
            DebugLogger.warning("[Pack] Note-block carrier produced no allocatable states; pack blocks are disabled.");
        }
        return new StateTable(material, allocatable, all);
    }

    /** Extracts the property part of a serialized block state, e.g. {@code note=5,instrument=zombie,powered=false}. */
    private static String stateKey(String serialized) {
        int open = serialized.indexOf('[');
        int close = serialized.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return "";
        }
        return serialized.substring(open + 1, close);
    }

    private static boolean isRareInstrument(String stateKey) {
        for (String part : stateKey.split(",")) {
            if (part.startsWith("instrument=")) {
                return RARE_INSTRUMENTS.contains(part.substring("instrument=".length()));
            }
        }
        return false;
    }

    private StateTable table() {
        StateTable table = TABLES.get(this);
        if (table == null) {
            warmUp();
            table = TABLES.get(this);
        }
        return table == null ? new StateTable(null, List.of(), List.of()) : table;
    }

    /** The carrier block material. Bukkit object — only valid after {@link #warmUp()} on the main thread. */
    public Material material() {
        return table().material();
    }

    /** Full registry id, e.g. {@code minecraft:note_block}. */
    public String materialId() {
        return materialId;
    }

    /** Registry path without the namespace, e.g. {@code note_block} (blockstate host file name). */
    public String materialKey() {
        int colon = materialId.indexOf(':');
        return colon >= 0 ? materialId.substring(colon + 1) : materialId;
    }

    /** Number of allocatable slots (0 when the carrier is unavailable). */
    public int stateCount() {
        return table().allocatable().size();
    }

    /** The complete blockstate string for one slot, e.g. {@code note=3,instrument=zombie,powered=false}. */
    public String stateString(int slot) {
        List<String> allocatable = table().allocatable();
        if (slot < 0 || slot >= allocatable.size()) {
            throw new IllegalArgumentException("Carrier " + name() + " slot out of range: " + slot);
        }
        return allocatable.get(slot);
    }

    /** The model vanilla renders for an unallocated state. */
    public String vanillaModel() {
        return vanillaModel;
    }

    /**
     * Every vanilla state of this carrier mapped to the model vanilla renders
     * for it, in deterministic enumeration order. The generated blockstate host
     * file starts from this table and overrides the allocated slots.
     */
    public Map<String, String> vanillaVariants() {
        Map<String, String> variants = new LinkedHashMap<>();
        for (String state : table().all()) {
            variants.put(state, vanillaModel);
        }
        return variants;
    }

    /** Parses a carrier key; unknown or blank values fall back to {@link #NOTE_BLOCK}. */
    public static PackBlockCarrier fromKey(String key, PackBlockCarrier fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return PackBlockCarrier.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private record StateTable(Material material, List<String> allocatable, List<String> all) {
        private StateTable {
            allocatable = List.copyOf(allocatable);
            all = List.copyOf(all);
        }
    }
}
