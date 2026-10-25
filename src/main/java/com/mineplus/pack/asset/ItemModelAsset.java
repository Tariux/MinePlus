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
 * <p>The modern representation references the geometry at
 * {@code <namespace>:item/<id>} via the {@code item_model} component; the
 * legacy representation allocates a stable predicate
 * {@code custom_model_data} value derived from the item id.</p>
 *
 * <p><b>Framed items</b> are assembled from verbatim model JSONs registered by
 * the module ({@code RawAsset}s); their geometry reference is null and the
 * emitted definition dispatches over registered frames by the item's
 * {@code custom_model_data} value, falling back to {@code fallbackModelReference}.
 * Framed items have no legacy representation: the legacy host overrides cannot
 * resolve their raw models, so they are excluded from host files and the
 * factory renders their backing material bare.</p>
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
        super(namespace, "item/" + requireItemToken(id), owner);
        this.backingMaterial = Objects.requireNonNull(backingMaterial, "backingMaterial");
        this.displayName = displayName == null ? "" : displayName;
        this.fallbackModelReference = fallbackModelReference == null || fallbackModelReference.isBlank()
                ? null
                : fallbackModelReference;
        this.frames = frames == null ? List.of() : List.copyOf(sanitize(frames));
        // A geometry-bound asset owns a registered virtual model; framed
        // (raw-model) items don't and must never appear in legacy host files.
        this.geometryBound = this.frames.isEmpty();
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

    /** True when this item resolves to a registered virtual model (legacy-representable). */
    public boolean isGeometryBound() {
        return geometryBound;
    }

    /**
     * Stable legacy {@code custom_model_data} predicate value derived from the
     * item id — deterministic across restarts so previously issued items keep
     * rendering, never zero (zero would match every vanilla item).
     * {@code floorMod} avoids the {@code Math.abs(Integer#MIN_VALUE)}
     * overflow that would produce an invalid negative predicate. Must stay in sync with
     * {@code PackItemFactory.stableCustomModelData}.
     */
    public int customModelData() {
        int hash = (namespace() + ":" + itemId()).hashCode();
        return 1000 + Math.floorMod(hash, 900_000);
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
            framePart = builder.toString();
        }
        return (namespace() + ":" + itemId() + "|" + backingMaterial.name() + "|" + displayName + framePart)
                .getBytes(StandardCharsets.UTF_8);
    }
}
