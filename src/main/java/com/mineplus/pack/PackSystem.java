package com.mineplus.pack;

import com.mineplus.infrastructure.core.multiblock.render.ModelRenderingManager;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.MineplusPlugin;
import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.compile.PackCache;
import com.mineplus.pack.compile.PackCompiler;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.asset.ModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.pack.asset.RawAsset;
import com.mineplus.pack.asset.TextureAsset;
import com.mineplus.pack.item.PackItemDefinition;
import com.mineplus.pack.item.PackItemFactory;
import com.mineplus.pack.render.PackModelRenderer;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Composition root and lifecycle owner of the resource pack subsystem — the
 * peer of the virtual rendering engine. Owns the asset registry, the compiler
 * (on its own bounded executor), the artifact cache, delivery, the player
 * tracker and the pack world renderer; each piece keeps a single
 * responsibility and this class only wires and sequences them.
 *
 * <p>Registration is order-insensitive: items registered before their models
 * load (the normal module bootstrap order) bind their identity immediately and
 * their model/texture assets attach during {@link #onReload()}, after the
 * coordinated model load. Every registration coalesces into at most one
 * pending compile.</p>
 *
 * <p>Failure isolation: a registration or compile failure never propagates to
 * the caller's plugin lifecycle — the offending asset is reported and the
 * rest of the pack continues.</p>
 */
public final class PackSystem {

    private final MineplusPlugin plugin;
    private final VirtualBlockManager virtualBlockManager;
    private final ItemRegistry itemRegistry;
    private final PackSettings settings;

    private final PackAssetRegistry assetRegistry = new PackAssetRegistry();
    private final PackCache cache;
    private final PackCompiler compiler;
    private final PackItemFactory itemFactory;
    private final PackModelRenderer modelRenderer;
    private final PackDeliveryService delivery;
    private final List<PackItemDefinition> registeredItems = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ExecutorService compileExecutor;
    private final AtomicBoolean pendingCompile = new AtomicBoolean(false);
    private volatile PackArtifact currentArtifact;
    private volatile boolean running;

    private PackSystem(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            ItemRegistry itemRegistry,
            PackSettings settings
    ) {
        this.plugin = plugin;
        this.virtualBlockManager = virtualBlockManager;
        this.itemRegistry = itemRegistry;
        this.settings = settings;
        this.cache = new PackCache(plugin.getDataFolder());
        this.compiler = new PackCompiler(virtualBlockManager, cache, settings);
        this.itemFactory = new PackItemFactory(
                PackFormat.itemRepresentation(org.bukkit.Bukkit.getBukkitVersion()));
        this.modelRenderer = new PackModelRenderer(assetRegistry, itemFactory);
        this.modelRenderer.bindModelsReloadedHook(this::onReload);
        this.delivery = new PackDeliveryService(settings, cache);
        this.compileExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mineplus-pack-compile");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the subsystem: delivery endpoint, listeners, renderer injection
     * into the rendering manager, and the initial compile. Never throws — a
     * pack failure must not take the plugin down.
     */
    public static PackSystem start(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            ItemRegistry itemRegistry,
            ModelRenderingManager renderingManager,
            PackSettings settings
    ) {
        PackSystem system = new PackSystem(plugin, virtualBlockManager, itemRegistry, settings);
        system.startInternal(renderingManager);
        return system;
    }

    private void startInternal(ModelRenderingManager renderingManager) {
        try {
            running = true;
            if (!delivery.start()) {
                plugin.getLogger().warning("[Pack] Local delivery endpoint unavailable; "
                        + "STATIC_URL/DISABLED modes still work.");
            }
            plugin.getServer().getPluginManager().registerEvents(
                    new PackPlayerTracker(this), plugin);
            plugin.getServer().getPluginManager().registerEvents(modelRenderer, plugin);
            renderingManager.setPackRenderer(modelRenderer);
            scheduleRecompile();
            plugin.getLogger().info("[Pack] Resource pack subsystem active (delivery="
                    + settings.deliveryMode() + ").");
        } catch (Throwable failure) {
            running = false;
            plugin.getLogger().warning("[Pack] Subsystem failed to start; virtual rendering unaffected: "
                    + failure.getMessage());
        }
    }

    public void shutdown() {
        running = false;
        compileExecutor.shutdownNow();
        delivery.stop();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------
    // Registration surface (used by PackApi)
    // ------------------------------------------------------------------

    /** Registers a custom item: identity + assets + ItemRegistry recognition. */
    public void registerItem(PackItemDefinition definition) {
        if (definition == null) {
            return;
        }
        try {
            ItemModelAsset itemAsset = new ItemModelAsset(
                    definition.namespace(), definition.id(), definition.namespace(),
                    definition.backingMaterial(), definition.displayName());
            assetRegistry.register(itemAsset);
            assetRegistry.bindItemToModel(definition.modelKey(), itemAsset);
            definition.bindFactory(itemFactory);
            itemRegistry.register(definition);
            registeredItems.add(definition);
            ensureModelAssets(definition);
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /** Registers a standalone model asset (referenced by items or raw pack content). */
    public void registerModel(String namespace, String path, String modelKey) {
        VirtualModel model = virtualBlockManager.getModel(modelKey);
        if (model == null) {
            DebugLogger.warning("[Pack] registerModel: model key '" + modelKey
                    + "' is not loaded yet; asset will attach on the next reload.");
            return;
        }
        try {
            assetRegistry.register(new ModelAsset(namespace, path, namespace,
                    modelKey, virtualBlockManager.getModelSourceFile(modelKey)));
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /** Registers a texture file asset. */
    public void registerTexture(String namespace, String path, File file) {
        try {
            assetRegistry.register(new TextureAsset(namespace, path, namespace, file));
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /** Registers a verbatim asset (sounds, lang, other files). */
    public void registerRawAsset(String namespace, String path, File file) {
        try {
            assetRegistry.register(new RawAsset(namespace, path, namespace, file));
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /**
     * After the coordinated model reload: attach model/texture assets for
     * items whose models just became available, then recompile once.
     */
    public void onReload() {
        if (!running) {
            return;
        }
        for (PackItemDefinition definition : registeredItems) {
            ensureModelAssets(definition);
        }
        scheduleRecompile();
    }

    /**
     * Idempotently attaches the geometry + texture assets for one item when
     * its model is loaded. Texture discovery reuses the existing model/texture
     * system: PNGs resolved exactly like the texel baker resolves them.
     */
    private void ensureModelAssets(PackItemDefinition definition) {
        VirtualModel model = virtualBlockManager.getModel(definition.modelKey());
        if (model == null) {
            return;
        }
        String namespace = definition.namespace();
        try {
            assetRegistry.register(new ModelAsset(namespace, "item/" + definition.id(), namespace,
                    definition.modelKey(), virtualBlockManager.getModelSourceFile(definition.modelKey())));
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
            return;
        }
        for (String textureName : model.textureNames()) {
            if (textureName == null || textureName.isBlank()) {
                continue;
            }
            try {
                File textureFile = virtualBlockManager.resolveTextureFile(definition.modelKey(), textureName);
                if (textureFile != null) {
                    assetRegistry.register(new TextureAsset(namespace, textureName, namespace, textureFile));
                }
            } catch (IllegalArgumentException conflict) {
                // Same texture id registered with different bytes — a real
                // cross-module conflict worth surfacing, not a silent skip.
                plugin.getLogger().warning("[Pack] " + conflict.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    /** Coalescing compile request: bursts collapse to at most one queued compile. */
    private void scheduleRecompile() {
        if (!running || !pendingCompile.compareAndSet(false, true)) {
            return;
        }
        compileExecutor.execute(() -> {
            pendingCompile.set(false);
            compileNow();
        });
    }

    /** Explicit recompile (command/API); returns the compile outcome. */
    public CompletableFuture<PackArtifact> recompile() {
        CompletableFuture<PackArtifact> future = new CompletableFuture<>();
        if (!running) {
            future.complete(null);
            return future;
        }
        compileExecutor.execute(() -> {
            try {
                future.complete(compileNow());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private PackArtifact compileNow() {
        try {
            // Safety net alongside the reload hook: attach any assets whose
            // models have become available since the last pass (idempotent).
            for (PackItemDefinition definition : registeredItems) {
                ensureModelAssets(definition);
            }
            List<PackAsset> snapshot = assetRegistry.snapshot();
            if (snapshot.isEmpty()) {
                DebugLogger.info("[Pack] No assets registered; nothing to compile.");
                return null;
            }
            PackArtifact artifact = compiler.compile(snapshot);
            if (artifact != null) {
                currentArtifact = artifact;
                delivery.publish(artifact);
            }
            return artifact;
        } catch (Throwable failure) {
            plugin.getLogger().warning("[Pack] Compilation failed; previous artifact kept: "
                    + failure.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Runtime accessors
    // ------------------------------------------------------------------

    public PackArtifact currentArtifact() {
        return currentArtifact;
    }

    public PackAssetRegistry assetRegistry() {
        return assetRegistry;
    }

    public PackModelRenderer modelRenderer() {
        return modelRenderer;
    }

    public PackItemFactory itemFactory() {
        return itemFactory;
    }

    public PackDeliveryService delivery() {
        return delivery;
    }

    public boolean deliverPack(Player player) {
        return delivery.deliver(player);
    }

    public PlayerPackState playerPackState(UUID playerId) {
        return delivery.state(playerId);
    }

    /** Stack for a registered item definition (identity carries the namespace). */
    public ItemStack createItem(String namespace, String id) {
        if (namespace == null || id == null) {
            return null;
        }
        for (PackItemDefinition definition : registeredItems) {
            if (definition.namespace().equals(namespace) && definition.id().equals(id)) {
                return itemRegistry.createItem(definition.key());
            }
        }
        return null;
    }

    /** Player-state hook (tracker callback; future feature surfaces subscribe here). */
    public void onPlayerPackState(UUID playerId, PlayerPackState state) {
        if (state == PlayerPackState.APPLIED) {
            DebugLogger.info("[Pack] Player " + playerId + " applied the pack.");
        }
    }

    public MineplusPlugin plugin() {
        return plugin;
    }
}
