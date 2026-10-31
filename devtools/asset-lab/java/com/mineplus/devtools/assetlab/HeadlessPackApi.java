package com.mineplus.devtools.assetlab;

import com.mineplus.pack.PackApi;
import com.mineplus.pack.PackArtifact;
import com.mineplus.pack.PlayerPackState;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A capturing {@link PackApi} for the headless Asset Lab daemon: available,
 * display-capable, and every generated-asset registration is recorded —
 * everything else is a safe no-op. This lets the tool run the real
 * {@code ModelBundleCompiler}/{@code PackModelEmitter} chain offline and report
 * exactly what the server would compile into the pack (pieces, splits, repairs)
 * without a Bukkit server, a pack artifact, or any I/O.
 */
final class HeadlessPackApi implements PackApi {

    private final Map<String, byte[]> generatedAssets = new LinkedHashMap<>();
    private final Map<String, Integer> displayModels = new LinkedHashMap<>();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsGeneratedModelDisplay() {
        return true;
    }

    @Override
    public void registerGeneratedAsset(String namespace, String path, byte[] bytes) {
        if (namespace == null || path == null || bytes == null) {
            return;
        }
        generatedAssets.put(namespace + ":" + path, bytes.clone());
    }

    @Override
    public void registerDisplayModel(Material material, String namespace, String componentPath) {
        if (namespace == null || componentPath == null) {
            return;
        }
        displayModels.merge(namespace + ":" + componentPath, 1, Integer::sum);
    }

    /** Captured generated assets keyed {@code namespace:path} (registration order). */
    Map<String, byte[]> generatedAssets() {
        return generatedAssets;
    }

    /** Display-model paths reserved for legacy custom_model_data predicates. */
    Map<String, Integer> displayModels() {
        return displayModels;
    }

    // ------------------------------------------------------------------
    // Never invoked by the compile chain — safe no-ops.
    // ------------------------------------------------------------------

    @Override
    public void registerItem(com.mineplus.pack.item.PackItemDefinition definition) {
    }

    @Override
    public void registerModel(String namespace, String path, String modelKey) {
    }

    @Override
    public void registerBlock(com.mineplus.pack.block.PackBlockDefinition definition) {
    }

    @Override
    public void registerTexture(String namespace, String path, File file) {
    }

    @Override
    public void registerRawAsset(String namespace, String path, File file) {
    }

    @Override
    public ItemStack createItem(String namespace, String id) {
        return null;
    }

    @Override
    public PlayerPackState playerPackState(UUID playerId) {
        return PlayerPackState.UNKNOWN;
    }

    @Override
    public boolean deliverPack(Player player) {
        return false;
    }

    @Override
    public CompletableFuture<PackArtifact> recompile() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public PackArtifact currentArtifact() {
        return null;
    }
}
