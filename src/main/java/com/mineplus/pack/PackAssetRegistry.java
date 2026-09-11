package com.mineplus.pack;

import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.util.DebugLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of every asset that goes into the generated resource pack, keyed by
 * the asset's {@link PackAsset#zipEntryPath() pack entry path} — the file it
 * writes inside the zip. That is the real collision domain: an item definition
 * ({@code assets/<ns>/items/<id>.json}) and its geometry model
 * ({@code assets/<ns>/models/item/<id>.json}) legitimately share the asset id
 * {@code <ns>:item/<id>} but must coexist, while two assets writing the same
 * entry are a genuine conflict, rejected loudly instead of silently
 * overwriting another module's file.
 *
 * <p>The registry is concurrent (registration from any thread) but compiles
 * against an immutable, deterministically ordered snapshot, so identical
 * registrations always produce byte-identical packs.</p>
 */
public final class PackAssetRegistry {

    private final Map<String, PackAsset> assets = new ConcurrentHashMap<>();
    /** modelKey -> ModelAsset, so world renders can resolve an item by model. */
    private final Map<String, ItemModelAsset> itemsByModelKey = new ConcurrentHashMap<>();

    /**
     * Registers an asset.
     *
     * @throws IllegalArgumentException on an entry-path collision with a
     *                                  different asset (the same entry
     *                                  re-registered with identical content is
     *                                  a no-op)
     */
    public void register(PackAsset asset) {
        String entryPath = asset.zipEntryPath();
        PackAsset existing = assets.putIfAbsent(entryPath, asset);
        if (existing != null && isIdentical(existing, asset)) {
            return; // identical re-registration (reload)
        }
        if (existing != null) {
            throw new IllegalArgumentException("Pack asset conflict on '" + entryPath + "': registered by '"
                    + existing.owner() + "' (" + existing.id() + "), re-registered by '"
                    + asset.owner() + "' (" + asset.id() + ").");
        }
        DebugLogger.info("[Pack] Registered asset '" + asset.id() + "' -> " + entryPath
                + " (owner '" + asset.owner() + "').");
    }

    private boolean isIdentical(PackAsset existing, PackAsset asset) {
        if (existing.getClass() != asset.getClass()) {
            return false;
        }
        try {
            return existing.contentHash().equals(asset.contentHash());
        } catch (IOException unreadable) {
            DebugLogger.warning("[Pack] Could not hash '" + asset.id()
                    + "' for identity check: " + unreadable.getMessage());
            return false;
        }
    }

    /** Binds a pack item to a virtual model key (world rendering lookup). */
    public void bindItemToModel(String modelKey, ItemModelAsset item) {
        itemsByModelKey.put(modelKey.toLowerCase(Locale.ROOT), item);
    }

    /** The pack item rendering the given virtual model key, or {@code null}. */
    public ItemModelAsset itemForModel(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return null;
        }
        return itemsByModelKey.get(modelKey.trim().toLowerCase(Locale.ROOT));
    }

    /** The asset writing the given pack entry path, or {@code null}. */
    public PackAsset get(String entryPath) {
        return assets.get(entryPath);
    }

    public int size() {
        return assets.size();
    }

    /** Immutable, deterministically ordered compile snapshot. */
    public List<PackAsset> snapshot() {
        List<PackAsset> sorted = new ArrayList<>(assets.values());
        sorted.sort(Comparator.comparing(PackAsset::id));
        return sorted;
    }

    /** Drops all registrations (full reload); the compiled artifact cache is unaffected. */
    public void clear() {
        assets.clear();
        itemsByModelKey.clear();
    }
}
