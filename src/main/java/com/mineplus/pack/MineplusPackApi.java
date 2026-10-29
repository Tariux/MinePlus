package com.mineplus.pack;

import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.block.PackBlockDefinition;
import com.mineplus.pack.item.PackItemDefinition;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Live {@link PackApi} over a running {@link PackSystem}. Thin by design:
 * every responsibility lives in the system's collaborators, never here.
 */
public final class MineplusPackApi implements PackApi {

    private final PackSystem system;

    public MineplusPackApi(PackSystem system) {
        this.system = system;
    }

    @Override
    public boolean isAvailable() {
        return system != null && system.isRunning();
    }

    @Override
    public boolean usesModernItemModels() {
        return isAvailable() && system.usesModernItemModels();
    }

    @Override
    public void registerItem(PackItemDefinition definition) {
        system.registerItem(definition);
    }

    @Override
    public void registerModel(String namespace, String path, String modelKey) {
        system.registerModel(namespace, path, modelKey);
    }

    @Override
    public void registerBlock(PackBlockDefinition definition) {
        system.registerBlock(definition);
    }

    @Override
    public void registerTexture(String namespace, String path, File file) {
        system.registerTexture(namespace, path, file);
    }

    @Override
    public void registerRawAsset(String namespace, String path, File file) {
        system.registerRawAsset(namespace, path, file);
    }

    @Override
    public void registerGeneratedAsset(String namespace, String path, byte[] bytes) {
        system.registerGeneratedAsset(namespace, path, bytes);
    }

    @Override
    public ItemStack createItem(String namespace, String id) {
        return system.createItem(namespace, id);
    }

    @Override
    public boolean supportsGeneratedModelDisplay() {
        return isAvailable() && system.supportsGeneratedModelDisplay();
    }

    @Override
    public ItemStack createModelDisplay(org.bukkit.Material material, String namespace, String componentPath) {
        return isAvailable() ? system.createModelDisplay(material, namespace, componentPath) : null;
    }

    @Override
    public PlayerPackState playerPackState(UUID playerId) {
        return system.playerPackState(playerId);
    }

    @Override
    public boolean deliverPack(Player player) {
        return system.deliverPack(player);
    }

    @Override
    public String deliveryUrl(Player player) {
        return system.deliveryUrlFor(player);
    }

    @Override
    public CompletableFuture<PackArtifact> recompile() {
        return system.recompile();
    }

    @Override
    public PackArtifact currentArtifact() {
        return system.currentArtifact();
    }
}
