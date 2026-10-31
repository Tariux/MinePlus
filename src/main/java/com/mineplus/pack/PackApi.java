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
     * True when this server presents items through modern item-model
     * definitions (1.21.4+) rather than legacy {@code custom_model_data}
     * predicates. Only the modern representation can serialize a client-clock
     * animation, so a module checks this before deciding how to drive frame
     * states. False when the subsystem is disabled or the server is older.
     */
    default boolean usesModernItemModels() {
        return false;
    }

    /**
     * The configured appearance mode ({@code PACK.APPEARANCE_MODE});
     * {@link com.mineplus.pack.asset.AppearanceMode#AUTO} when the subsystem
     * has no settings or is disabled. Frame state writes need no module
     * change: {@code ItemFrameWriter} follows the resolved channel.
     */
    default com.mineplus.pack.asset.AppearanceMode appearanceMode() {
        return com.mineplus.pack.asset.AppearanceMode.AUTO;
    }

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
     * Registers a custom block: the model that supplies its geometry and the
     * carrier state its {@code BlockDisplay} renders through. The block is
     * placed and collision-owned by the ordinary multiblock lifecycle
     * ({@code createMultiBlock}/{@code placeMultiBlock}/{@code removeBlock});
     * this call only declares the pack rendering. The multiblock level selects
     * it with {@code "renderBackend": "pack"} and {@code "renderKind": "block"}.
     *
     * @param definition the block definition
     */
    void registerBlock(PackBlockDefinition definition);

    /**
     * Registers a bone rig: the model whose group hierarchy supplies one
     * display per bone. The level selects it with {@code "renderBackend":
     * "pack"} and {@code "renderKind": "bones"} ({@code "renderMode":
     * "pack_bones"}); placement and collision stay with the ordinary
     * multiblock lifecycle.
     *
     * @param definition the rig definition
     */
    default void registerBoneRig(com.mineplus.pack.rig.PackBoneRigDefinition definition) {
    }

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

    /**
     * Registers a file whose bytes a subsystem composed in memory (a generated
     * model JSON, a synthesized definition). The same entry re-registered by
     * the same owner replaces the previous generation, so a derived asset can
     * follow its source definition across reloads.
     *
     * @param namespace asset namespace
     * @param path      output path under {@code assets/<ns>/}
     * @param bytes     exact file contents
     */
    default void registerGeneratedAsset(String namespace, String path, byte[] bytes) {
    }

    /**
     * Declares that a generated model path is also a world-display model: the
     * model may be carried by an {@code ItemDisplay} through
     * {@link #createModelDisplay}. On servers with the modern
     * {@code item_model} component this is implicit — the component names the
     * model directly. On legacy servers a display item can only render a
     * model through a {@code custom_model_data} override, so the pack reserves
     * one host-file predicate for the path here. Registering the same path
     * twice is a no-op, so a subsystem may call it on every reload.
     *
     * @param material      the invisible carrier material
     * @param namespace     model namespace
     * @param componentPath model path under {@code models/item/} (slashes allowed)
     */
    default void registerDisplayModel(org.bukkit.Material material, String namespace, String componentPath) {
    }

    /** Creates the registered item's stack (identity + presentation), or null. */
    ItemStack createItem(String namespace, String id);

    /**
     * True when this server can present a generated model through an
     * {@code ItemDisplay} — the capability a world renderer needs to build a
     * rig out of per-part models. False when the pack subsystem is unavailable.
     */
    default boolean supportsGeneratedModelDisplay() {
        return false;
    }

    /**
     * The display stack for a generated model at an arbitrary pack path, or
     * {@code null} when {@link #supportsGeneratedModelDisplay()} is false.
     * Used by world renderers whose model paths are not item definitions
     * (e.g. an entity rig's per-part models under {@code item/entity/...}).
     *
     * @param material      the invisible carrier material
     * @param namespace     model namespace
     * @param componentPath item-model path (slashes allowed)
     */
    default ItemStack createModelDisplay(org.bukkit.Material material, String namespace, String componentPath) {
        return null;
    }

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
    public void registerBlock(PackBlockDefinition definition) {
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
