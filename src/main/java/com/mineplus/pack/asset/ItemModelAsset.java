package com.mineplus.pack.asset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;

/**
 * One custom item definition: the Mineplus identity ({@code namespace:id}) and
 * how it presents. The backing vanilla material is only an implementation
 * carrier — vanilla items never carry Mineplus state, so they render and
 * behave completely vanilla (vanilla preservation contract).
 *
 * <p>The modern representation resolves this item's definition through the
 * {@code item_model} component ({@code <namespace>:<id>} →
 * {@code assets/<ns>/items/<id>.json}); the definition in turn points at the
 * geometry model {@code <namespace>:item/<id>}
 * ({@code assets/<ns>/models/item/<id>.json}). The legacy representation
 * allocates a stable predicate {@code custom_model_data} value derived from
 * the item id.</p>
 *
 * <p>Frames are one feature across both representations: modern (1.21.4+)
 * serializes a {@code range_dispatch} over the frame states keyed by the
 * item's {@code custom_model_data} value; legacy (pre-1.21.4) emits one
 * host-file predicate override per frame state
 * ({@code custom_model_data = frameStackValue(state)}). Both read the same
 * integer the runtime writes, so a module drives frames identically on every
 * server version by writing {@code frameStackValue(state)}.
 */
public final class ItemModelAsset extends PackAsset {

    /** One dispatch step: renders {@code modelReference} once the item's custom-model-data value clears {@code threshold}. */
    public record Frame(float threshold, String modelReference) {
        public Frame {
            if (modelReference == null || modelReference.isBlank()) {
                throw new IllegalArgumentException("Frame model reference is blank");
            }
        }
    }

    private final Material backingMaterial;
    private final String displayName;
    private final String fallbackModelReference;
    private final List<Frame> frames;
    private final com.mineplus.pack.item.ItemFrames.ClipPlan clipPlan;
    private final boolean geometryBound;

    /**
     * @param namespace item namespace (the {@code <ns>} of {@code <ns>:<id>})
     * @param id        item id (also the model path {@code item/<id>})
     * @param owner     registering module name
     * @param backingMaterial the vanilla material backing the ItemStack
     * @param displayName optional display name; blank = none
     */
    public ItemModelAsset(String namespace, String id, String owner, Material backingMaterial, String displayName) {
        this(namespace, id, owner, backingMaterial, displayName, null, List.of());
    }

    /** Framed variant: raw model references dispatched by custom-model-data. */
    public ItemModelAsset(String namespace, String id, String owner, Material backingMaterial, String displayName,
                          String fallbackModelReference, List<Frame> frames) {
        this(namespace, id, owner, backingMaterial, displayName, fallbackModelReference, frames,
                com.mineplus.pack.item.ItemFrames.ClipPlan.NONE);
    }

    /** Framed variant with the client-driven reload/shot sequences of the spec. */
    public ItemModelAsset(String namespace, String id, String owner, Material backingMaterial, String displayName,
                          String fallbackModelReference, List<Frame> frames,
                          com.mineplus.pack.item.ItemFrames.ClipPlan clipPlan) {
        super(namespace, "item/" + requireItemToken(id), owner);
        this.backingMaterial = Objects.requireNonNull(backingMaterial, "backingMaterial");
        this.displayName = displayName == null ? "" : displayName;
        this.fallbackModelReference = fallbackModelReference == null || fallbackModelReference.isBlank()
                ? null
                : fallbackModelReference;
        this.frames = frames == null ? List.of() : List.copyOf(sanitize(frames));
        this.clipPlan = clipPlan == null ? com.mineplus.pack.item.ItemFrames.ClipPlan.NONE : clipPlan;
        // A geometry-bound asset owns a registered virtual model; raw-model
        // items (fallback reference set — with or without dispatch frames,
        // e.g. a plain imported model) don't and reduce to a single
        // host-file override instead of a generated model asset.
        this.geometryBound = this.frames.isEmpty() && this.fallbackModelReference == null;
    }

    private static List<Frame> sanitize(List<Frame> raw) {
        List<Frame> sorted = new ArrayList<>(raw);
        sorted.sort(java.util.Comparator.comparingDouble(Frame::threshold));
        return sorted;
    }

    private static String requireItemToken(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid pack item id: '" + id + "'");
        }
        return normalized;
    }

    /** The item id segment ({@code <ns>:<this>}). */
    public String itemId() {
        return path().substring("item/".length());
    }

    public Material backingMaterial() {
        return backingMaterial;
    }

    public String displayName() {
        return displayName;
    }

    /** The model this item renders, always {@code <namespace>:item/<id>}. */
    public String modelReference() {
        return namespace() + ":" + path();
    }

    /** Dispatch frames of this item, threshold-sorted; empty on plain items. */
    public List<Frame> frames() {
        return frames;
    }

    /** Model rendered at the resting state of a framed item; {@code null} on plain items. */
    public String fallbackModelReference() {
        return fallbackModelReference;
    }

    /** True when the item carries dispatch frames (raw model JSONs, modern representation only). */
    public boolean isFramed() {
        return !frames.isEmpty();
    }

    /** The client-driven reload/shot sequences of this item, never null. */
    public com.mineplus.pack.item.ItemFrames.ClipPlan clipPlan() {
        return clipPlan;
    }

    /** True when the item's models are raw references registered by the module (no virtual geometry). */
    public boolean isRawModel() {
        return fallbackModelReference != null;
    }

    /** True when this item resolves to a registered virtual model (legacy-representable). */
    public boolean isGeometryBound() {
        return geometryBound;
    }

    /**
     * Stable legacy {@code custom_model_data} predicate value derived from the
     * item id — deterministic across restarts so previously issued items keep
     * rendering, never zero (zero would match every vanilla item).
     * {@code floorMod} avoids the {@code Math.abs(Integer#MIN_VALUE)}
     * overflow that would produce an invalid negative predicate. This is also
     * the base of a framed item's state values. Must stay in sync with
     * {@code PackItemFactory}'s legacy custom-model-data base.
     */
    public int customModelData() {
        return frameBase(namespace(), itemId());
    }

    /** The stable per-item custom-model-data base of {@code <namespace>:<id>}. */
    public static int frameBase(String namespace, String itemId) {
        int hash = (namespace + ":" + itemId).hashCode();
        return 1000 + Math.floorMod(hash, 900_000);
    }

    /**
     * The stack value that renders one frame state of a framed item — the
     * item's base plus the state number, on every representation (modern
     * dispatch thresholds and legacy host predicates use exactly this value).
     * State {@code 0} is the resting state and yields the base itself. States
     * start at {@code 1} ({@link ItemFrames} numbering).
     */
    public static int frameStackValue(String namespace, String itemId, int state) {
        return frameBase(namespace, itemId) + Math.max(0, state);
    }

    /** The stack value of one of this item's frame states (see {@link #frameStackValue(String, String, int)}). */
    public int frameStackValue(int state) {
        return frameStackValue(namespace(), itemId(), state);
    }

    /** Modern representation entry: {@code assets/<ns>/items/<id>.json}. */
    public String modernEntryPath() {
        return "assets/" + namespace() + "/items/" + itemId() + ".json";
    }

    @Override
    public String zipEntryPath() {
        // The compiler routes modern entries through ItemModelWriter; legacy items
        // contribute overrides to a shared host file instead of owning an entry.
        return modernEntryPath();
    }

    @Override
    public byte[] serialize() throws IOException {
        return com.mineplus.pack.compile.ItemModelWriter.modernDefinitionJson(this)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] hashSource() {
        // Material enum name (lowercased = the registry key): avoids the
        // adventure Key type, whose examination supertype is not on the
        // compile classpath.
        String framePart = "";
        if (isFramed()) {
            StringBuilder builder = new StringBuilder();
            builder.append("|").append(fallbackModelReference);
            for (Frame frame : frames) {
                builder.append("|").append(frame.threshold()).append("=").append(frame.modelReference());
            }
            for (com.mineplus.pack.item.ItemFrames.Sequence sequence : clipPlan.sequences()) {
                builder.append("|seq:").append(sequence.name())
                        .append('@').append(sequence.gateState())
                        .append('=').append(String.join(",", sequence.frames()))
                        .append("->").append(sequence.fallbackReference());
            }
            framePart = builder.toString();
        }
        return (namespace() + ":" + itemId() + "|" + backingMaterial.name() + "|" + displayName + framePart)
                .getBytes(StandardCharsets.UTF_8);
    }
}
