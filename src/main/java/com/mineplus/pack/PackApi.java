package com.mineplus.pack;

import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.item.PackItemDefinition;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Developer API of the resource pack subsystem — a peer of
 * {@code InfrastructureApi} and {@code AnimationApi}, obtained through
 * {@code PluginContext.packApi()}. Modules register custom content and
 * consume delivery state; generated file paths, compilation internals and
 * NMS details never surface here.
 *
 * <p>The API is never null: when the pack subsystem is disabled, a no-op
 * implementation keeps every call safe so modules never branch on pack
 * availability for basic operation (their items simply render through the
 * vanilla backing material).</p>
 */
public interface PackApi {

    /** True when the pack subsystem is running (compiles, serves, pushes). */
    boolean isAvailable();

    /**
     * Registers a custom item: Mineplus identity, backing vanilla material,
     * and the registered model key that renders it. The item automatically
     * gains {@code ItemRegistry} recognition (PDC identity), and its
     * model/texture pack assets attach as soon as the model is loaded.
     *
     * @param definition the item definition
     */
    void registerItem(PackItemDefinition definition);

    /**
     * Registers a standalone model asset derived from a loaded virtual model.
     *
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/models/} (without {@code .json})
     * @param modelKey  key of the registered virtual model
     */
    void registerModel(String namespace, String path, String modelKey);

    /**
     * Registers a texture PNG asset.
     *
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/textures/} (without {@code .png})
     * @param file      the PNG file
     */
    void registerTexture(String namespace, String path, File file);

    /**
     * Registers a verbatim asset (sounds, language files, other pack files).
     *
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/}
     * @param file      the file
     */
    void registerRawAsset(String namespace, String path, File file);

    /** Creates the registered item's stack (identity + presentation), or null. */
    ItemStack createItem(String namespace, String id);

    /** A player's current pack state; {@link PlayerPackState#UNKNOWN} when unprompted. */
    PlayerPackState playerPackState(UUID playerId);

    /** Pushes the current pack to a player; false when undeliverable. */
    boolean deliverPack(Player player);

    /**
     * The URL one player's client would use to download the current pack
     * (per-player host resolution in LOCAL mode), or null when nothing is
     * deliverable. Diagnostics surface for status commands — not gameplay.
     */
    default String deliveryUrl(Player player) {
        return null;
    }

    /** Explicit recompile; completes with the artifact, or null when nothing compiled. */
    CompletableFuture<PackArtifact> recompile();

    /** The current artifact, or null before the first successful compile. */
    PackArtifact currentArtifact();

    /**
     * Fallback used when the subsystem is disabled: item identity still
     * registers (gameplay, recipes and recognition keep working); presentation
     * falls back to the vanilla backing material. Nothing compiles or pushes.
     */
    static PackApi disabled(com.mineplus.infrastructure.registry.ItemRegistry itemRegistry) {
        return new DisabledPackApi(itemRegistry);
    }
}

/** No-op implementation: every call safe, identity preserved, nothing pushed. */
final class DisabledPackApi implements PackApi {

    private final com.mineplus.infrastructure.registry.ItemRegistry itemRegistry;

    DisabledPackApi(com.mineplus.infrastructure.registry.ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void registerItem(PackItemDefinition definition) {
        if (definition != null && itemRegistry != null) {
            itemRegistry.register(definition);
        }
    }

    @Override
    public void registerModel(String namespace, String path, String modelKey) {
    }

    @Override
    public void registerTexture(String namespace, String path, File file) {
    }

    @Override
    public void registerRawAsset(String namespace, String path, File file) {
    }

    @Override
    public ItemStack createItem(String namespace, String id) {
        return itemRegistry == null || namespace == null || id == null
                ? null
                : itemRegistry.createItem(namespace + ":" + id);
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
