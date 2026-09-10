package com.mineplus.pack.asset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 */
public final class ItemModelAsset extends PackAsset {

    private final Material backingMaterial;
    private final String displayName;

    /**
     * @param namespace item namespace (the {@code <ns>} of {@code <ns>:<id>})
     * @param id        item id (also the model path {@code item/<id>})
     * @param owner     registering module name
     * @param backingMaterial the vanilla material backing the ItemStack
     * @param displayName optional display name; blank = none
     */
    public ItemModelAsset(String namespace, String id, String owner, Material backingMaterial, String displayName) {
        super(namespace, "item/" + requireItemToken(id), owner);
        this.backingMaterial = Objects.requireNonNull(backingMaterial, "backingMaterial");
        this.displayName = displayName == null ? "" : displayName;
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

    /**
     * Stable legacy {@code custom_model_data} predicate value derived from the
     * item id — deterministic across restarts so previously issued items keep
     * rendering, never zero (zero would match every vanilla item).
     */
    public int customModelData() {
        int hash = (namespace() + ":" + itemId()).hashCode();
        return 1000 + (Math.abs(hash) % 900_000);
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
        return (namespace() + ":" + itemId() + "|" + backingMaterial.name() + "|" + displayName)
                .getBytes(StandardCharsets.UTF_8);
    }
}
