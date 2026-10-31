package com.mineplus.pack;

import com.mineplus.infrastructure.core.multiblock.render.ModelRenderingManager;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.rig.BoneRigAssetWriter;
import com.mineplus.infrastructure.virtual.rig.BoneRigBlueprint;
import com.mineplus.infrastructure.virtual.rig.BoneRigDefinition;
import com.mineplus.infrastructure.virtual.rig.BoneRigRenderer;
import com.mineplus.infrastructure.virtual.rig.RigSettings;
import com.mineplus.MineplusPlugin;
import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.compile.PackCache;
import com.mineplus.pack.compile.PackCompiler;
import com.mineplus.pack.asset.BlockModelAsset;
import com.mineplus.pack.asset.ItemModelAsset;
import com.mineplus.pack.asset.ModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.pack.asset.PackBlockAsset;
import com.mineplus.pack.asset.RawAsset;
import com.mineplus.pack.asset.TextureAsset;
import com.mineplus.pack.block.PackBlockCarrier;
import com.mineplus.pack.block.PackBlockDefinition;
import com.mineplus.pack.item.PackItemDefinition;
import com.mineplus.pack.item.PackItemFactory;
import com.mineplus.pack.render.PackBlockRenderer;
import com.mineplus.pack.render.PackModelRenderer;
import com.mineplus.pack.rig.PackBoneRigDefinition;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.io.IOException;
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

    /** Ghost-cleanup tag prefix of the pack bone rig; the mob rig uses its own. */
    private static final String BONE_RIG_TAG_PREFIX = "mineplus_packrig:";

    private final MineplusPlugin plugin;
    private final VirtualBlockManager virtualBlockManager;
    private final ItemRegistry itemRegistry;
    private final PackSettings settings;

    private final PackAssetRegistry assetRegistry = new PackAssetRegistry();
    private final PackCache cache;
    private final PackCompiler compiler;
    private final PackItemFactory itemFactory;
    private final PackModelRenderer modelRenderer;
    private final PackBlockRenderer blockRenderer;
    private final BoneRigRenderer boneRenderer;
    private final PackDeliveryService delivery;
    private final List<PackItemDefinition> registeredItems = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<PackBlockDefinition> registeredBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<PackBoneRigDefinition> registeredBoneRigs = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** {@code namespace:id} -> rig binding, so reloads re-derive assets idempotently. */
    private final java.util.Map<String, PackBoneRigDefinition> boneRigsByKey = new java.util.concurrent.ConcurrentHashMap<>();
    /** {@code namespace:id} -> carrier binding, so reloads re-attach geometry idempotently. */
    private final java.util.Map<String, PackBlockAsset> blockAssetsByKey = new java.util.concurrent.ConcurrentHashMap<>();
    /** Allocated slots per carrier; a pack block owns one state per its carrier. */
    private final java.util.Map<PackBlockCarrier, java.util.BitSet> carrierSlots = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Legacy display models, keyed by their model reference
     * ({@code <ns>:item/<path>}). A generated rig model has no item id, so its
     * legacy presentation is a host-file override addressed by this map.
     */
    private final java.util.Map<String, ItemModelAsset> displayModels = new java.util.concurrent.ConcurrentHashMap<>();

    private final ExecutorService compileExecutor;
    private final AtomicBoolean pendingCompile = new AtomicBoolean(false);
    private volatile PackArtifact currentArtifact;
    private volatile boolean running;
    private volatile RigSettings rigSettings;
    /** Resolved appearance strategy of this session (configured mode × server capability). */
    private final com.mineplus.pack.asset.AppearanceMode.Resolved appearance;

    private PackSystem(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            ItemRegistry itemRegistry,
            PackSettings settings,
            RigSettings rigSettings
    ) {
        this.plugin = plugin;
        this.virtualBlockManager = virtualBlockManager;
        this.itemRegistry = itemRegistry;
        this.settings = settings;
        this.cache = new PackCache(plugin.getDataFolder());
        this.compiler = new PackCompiler(virtualBlockManager, cache, settings);
        this.appearance = settings.appearanceMode().resolve(
                PackFormat.itemRepresentation(org.bukkit.Bukkit.getBukkitVersion()),
                com.mineplus.pack.item.ItemFrameWriter.supportsStringState());
        com.mineplus.pack.item.ItemFrameWriter.setStringStates(
                appearance.dispatch() == com.mineplus.pack.asset.AppearanceMode.Dispatch.SELECT);
        this.itemFactory = new PackItemFactory(appearance.representation());
        this.modelRenderer = new PackModelRenderer(assetRegistry, itemFactory, settings.lighting());
        this.modelRenderer.bindModelsReloadedHook(this::onReload);
        this.blockRenderer = new PackBlockRenderer(assetRegistry, plugin.getLogger(), settings.lighting());
        RigSettings limits = rigSettings == null ? RigSettings.defaults() : rigSettings;
        this.rigSettings = limits;
        this.boneRenderer = new BoneRigRenderer(plugin, new MineplusPackApi(this),
                virtualBlockManager.displayTransport(), limits, BONE_RIG_TAG_PREFIX);
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
        return start(plugin, virtualBlockManager, itemRegistry, renderingManager, settings, null, null);
    }

    /**
     * @param perf performance registry the compiler reports build durations to
     *             (may be {@code null})
     */
    public static PackSystem start(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            ItemRegistry itemRegistry,
            ModelRenderingManager renderingManager,
            PackSettings settings,
            com.mineplus.infrastructure.perf.PerfRegistry perf
    ) {
        return start(plugin, virtualBlockManager, itemRegistry, renderingManager, settings, perf, null);
    }

    /**
     * @param perf        performance registry the compiler reports build durations to
     *                    (may be {@code null})
     * @param rigSettings bone-rig limits ({@code RIGS}); {@code null} = defaults
     */
    public static PackSystem start(
            MineplusPlugin plugin,
            VirtualBlockManager virtualBlockManager,
            ItemRegistry itemRegistry,
            ModelRenderingManager renderingManager,
            PackSettings settings,
            com.mineplus.infrastructure.perf.PerfRegistry perf,
            RigSettings rigSettings
    ) {
        PackSystem system = new PackSystem(plugin, virtualBlockManager, itemRegistry, settings,
                rigSettings == null ? RigSettings.defaults() : rigSettings);
        system.compiler.setPerfRegistry(perf);
        system.startInternal(renderingManager);
        return system;
    }

    /** Applies reloaded rig limits to the running renderer. */
    public void updateRigSettings(RigSettings rigSettings) {
        if (rigSettings != null) {
            this.rigSettings = rigSettings;
            boneRenderer.updateSettings(rigSettings);
        }
    }

    private void startInternal(ModelRenderingManager renderingManager) {
        try {
            running = true;
            // Enumerate carrier block states from the live registry now, on the
            // main thread; the async compiler then reads only cached strings.
            PackBlockCarrier.warmUp();
            if (!delivery.start()) {
                plugin.getLogger().warning("[Pack] Local delivery endpoint unavailable; "
                        + "STATIC_URL/DISABLED modes still work.");
            }
            plugin.getServer().getPluginManager().registerEvents(
                    new PackPlayerTracker(this), plugin);
            plugin.getServer().getPluginManager().registerEvents(modelRenderer, plugin);
            plugin.getServer().getPluginManager().registerEvents(blockRenderer, plugin);
            plugin.getServer().getPluginManager().registerEvents(boneRenderer, plugin);
            boneRenderer.setTransport(virtualBlockManager.displayTransport());
            renderingManager.setPackRenderer(modelRenderer);
            renderingManager.setPackBlockRenderer(blockRenderer);
            renderingManager.setPackBoneRenderer(boneRenderer);
            scheduleRecompile();
            plugin.getLogger().info("[Pack] Resource pack subsystem active (delivery="
                    + settings.deliveryMode()
                    + (delivery.localPort() > 0
                    ? ", endpoint port " + delivery.localPort()
                    + (delivery.localPortDrifted() ? " (configured port was busy)" : "")
                    : "")
                    + ").");
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

    /**
     * True when this server presents items through modern item-model
     * definitions ({@code assets/<ns>/items/<id>.json}, 1.21.4+) instead of
     * legacy {@code custom_model_data} predicates. The modern path is the only
     * one that can serialize a client-clock animation (nested
     * {@code range_dispatch} on {@code minecraft:cooldown}), so modules ask
     * this before choosing how to drive their frame states.
     */
    public boolean usesModernItemModels() {
        return appearance.modern();
    }

    /** The configured appearance mode (before capability resolution). */
    public com.mineplus.pack.asset.AppearanceMode appearanceMode() {
        return settings.appearanceMode();
    }

    /** The appearance strategy actually in force on this server. */
    public com.mineplus.pack.asset.AppearanceMode.Resolved appearance() {
        return appearance;
    }

    /** Content diagnostics of the last compile (empty when validation is off). */
    public com.mineplus.pack.compile.ModelAssetValidator.Report validationReport() {
        return compiler.lastReport();
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
            ItemModelAsset itemAsset = definition.modelKey() == null
                    ? new ItemModelAsset(
                            definition.namespace(), definition.id(), definition.namespace(),
                            definition.backingMaterial(), definition.displayName(),
                            definition.fallbackModelReference(), toFrameAssets(definition.frames()),
                            definition.plan())
                    : new ItemModelAsset(
                            definition.namespace(), definition.id(), definition.namespace(),
                            definition.backingMaterial(), definition.displayName());
            assetRegistry.register(itemAsset);
            if (definition.modelKey() != null) {
                assetRegistry.bindItemToModel(definition.modelKey(), itemAsset);
            }
            definition.bindFactory(itemFactory);
            itemRegistry.register(definition);
            registeredItems.add(definition);
            ensureModelAssets(definition);
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    private static List<ItemModelAsset.Frame> toFrameAssets(List<PackItemDefinition.Frame> frames) {
        List<ItemModelAsset.Frame> assets = new java.util.ArrayList<>(frames.size());
        for (PackItemDefinition.Frame frame : frames) {
            assets.add(new ItemModelAsset.Frame(frame.threshold(), frame.modelReference()));
        }
        return assets;
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
                    modelKey, virtualBlockManager.getModelSourceFile(modelKey), model));
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /**
     * Registers a custom block: Mineplus identity, the virtual model that
     * supplies its geometry, and the carrier pool its rendered
     * {@code BlockDisplay} state is allocated from. The block is placed and
     * collision-owned by the ordinary multiblock lifecycle; this registration
     * only declares its pack rendering. Assets attach on the next reload once
     * the model loads (order-insensitive, exactly like {@link #registerItem}).
     *
     * <p>Idempotent per {@code namespace:id}: re-registration reuses the
     * allocated carrier state and just re-attaches assets.</p>
     */
    public void registerBlock(PackBlockDefinition definition) {
        if (definition == null) {
            return;
        }
        try {
            String key = definition.namespace() + ":" + definition.id();
            PackBlockAsset existing = blockAssetsByKey.get(key);
            if (existing != null) {
                ensureBlockModelAssets(definition);
                scheduleRecompile();
                return;
            }
            int slot = allocateCarrierSlot(definition.carrier());
            if (slot < 0) {
                plugin.getLogger().warning("[Pack] No free " + definition.carrier()
                        + " carrier state for pack block '" + key + "'; block not registered ("
                        + definition.carrier().stateCount() + " slots in use).");
                return;
            }
            PackBlockAsset binding = new PackBlockAsset(
                    definition.namespace(), definition.id(), definition.namespace(),
                    definition.modelKey(), definition.carrier(), slot);
            assetRegistry.register(binding);
            assetRegistry.bindBlockToModel(definition.modelKey(), binding);
            blockAssetsByKey.put(key, binding);
            registeredBlocks.add(definition);
            plugin.getLogger().info("[Pack] Registered block '" + key + "' -> "
                    + binding.carrier().materialKey() + "[" + binding.stateString() + "]"
                    + " (model key '" + definition.modelKey() + "')");
            ensureBlockModelAssets(definition);
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /**
     * Registers a bone rig: the loaded model whose outliner groups become one
     * display each, compiled into per-bone item models. Placement, collision
     * and persistence stay with the ordinary multiblock lifecycle; this
     * registration only declares the pack rendering.
     *
     * <p>Idempotent per {@code namespace:id}: re-registration re-derives the
     * assets once the model is loaded (order-insensitive, like items and
     * blocks).</p>
     */
    public void registerBoneRig(PackBoneRigDefinition definition) {
        if (definition == null) {
            return;
        }
        try {
            String key = definition.contentId();
            if (boneRigsByKey.put(key, definition) == null) {
                registeredBoneRigs.add(definition);
            }
            ensureBoneRigAssets(definition);
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /** First free carrier slot, or -1 when the carrier is exhausted. */
    private synchronized int allocateCarrierSlot(PackBlockCarrier carrier) {        java.util.BitSet used = carrierSlots.computeIfAbsent(carrier, ignored -> new java.util.BitSet());
        int slot = used.nextClearBit(0);
        if (slot >= carrier.stateCount()) {
            return -1;
        }
        used.set(slot);
        return slot;
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
     * Registers in-memory generated bytes as a pack file owned by the Core.
     * Re-registration by the same owner replaces the previous generation, so
     * derived assets track their source definitions across reloads.
     */
    public void registerGeneratedAsset(String namespace, String path, byte[] bytes) {
        try {
            if (assetRegistry.registerGenerated(new com.mineplus.pack.asset.GeneratedAsset(
                    namespace, path, plugin.getName(), bytes))) {
                scheduleRecompile();
            }
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /** True when generated models can be displayed through an {@code ItemDisplay}. */
    public boolean supportsGeneratedModelDisplay() {
        // The modern representation names the model in the item_model component;
        // the legacy representation resolves it through the host-file override
        // registered by registerDisplayModel. Both can present a world model,
        // so availability is the only gate — a path with no override simply
        // yields no stack (see createModelDisplay) and the rig stays offline.
        return true;
    }

    /**
     * Reserves a legacy {@code custom_model_data} predicate for a generated
     * model path, so an {@code ItemDisplay} can carry it on servers without
     * the {@code item_model} component. The modern representation needs no
     * such reservation: the component already names the model.
     */
    public void registerDisplayModel(org.bukkit.Material material, String namespace, String componentPath) {
        if (material == null || namespace == null || componentPath == null || componentPath.isBlank()) {
            return;
        }
        if (itemFactory.supportsItemModelComponent()) {
            return; // the component names the model; no legacy predicate is needed
        }
        String reference = namespace + ":item/" + componentPath;
        String key = reference.toLowerCase(java.util.Locale.ROOT);
        if (displayModels.containsKey(key)) {
            return;
        }
        try {
            ItemModelAsset asset = new ItemModelAsset(namespace, displayItemId(componentPath), plugin.getName(),
                    material, "", reference, List.of());
            assetRegistry.register(asset);
            displayModels.put(key, asset);
            scheduleRecompile();
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        }
    }

    /**
     * A stable, legal item id for a display model path. Item ids admit only
     * {@code [a-z0-9_.-]}; the path keeps slashes for the model file, so the
     * id is a sanitized, prefixed form of the same path.
     */
    private static String displayItemId(String componentPath) {
        return "display_" + componentPath.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Display stack for a generated model path (see {@link PackApi#createModelDisplay}). */
    public ItemStack createModelDisplay(org.bukkit.Material material, String namespace, String componentPath) {
        if (material == null || namespace == null || componentPath == null) {
            return null;
        }
        if (itemFactory.supportsItemModelComponent()) {
            return itemFactory.createModelDisplay(material, namespace, componentPath);
        }
        ItemModelAsset asset = displayModels.get(
                (namespace + ":item/" + componentPath).toLowerCase(java.util.Locale.ROOT));
        return asset == null ? null : itemFactory.create(asset);
    }

    /**
     * After the coordinated model reload: attach model/texture assets for
     * items and blocks whose models just became available, then recompile once.
     */
    public void onReload() {
        if (!running) {
            return;
        }
        for (PackItemDefinition definition : registeredItems) {
            ensureModelAssets(definition);
        }
        for (PackBlockDefinition definition : registeredBlocks) {
            ensureBlockModelAssets(definition);
        }
        for (PackBoneRigDefinition definition : registeredBoneRigs) {
            ensureBoneRigAssets(definition);
        }
        scheduleRecompile();
    }

    /**
     * Idempotently attaches the geometry + texture assets for one item when
     * its model is loaded. Texture discovery reuses the existing model/texture
     * system: PNGs resolved exactly like the texel baker resolves them, and
     * registered under the same canonical path the serialized model
     * references (last-segment normalization shared with
     * {@code TextureAsset.normalizePath}).
     */
    private void ensureModelAssets(PackItemDefinition definition) {
        // Framed items carry no virtual geometry: their models are registered
        // raw by the owning module and referenced directly by the definition.
        if (definition.modelKey() == null) {
            return;
        }
        VirtualModel model = virtualBlockManager.getModel(definition.modelKey());
        if (model == null) {
            return;
        }
        String namespace = definition.namespace();
        try {
            assetRegistry.register(new ModelAsset(namespace, "item/" + definition.id(), namespace,
                    definition.modelKey(), virtualBlockManager.getModelSourceFile(definition.modelKey()), model));
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
            return;
        }
        registerModelTextures(namespace, definition.modelKey(), model, "item");
    }

    /**
     * Idempotently attaches the geometry + texture assets for one registered
     * pack block when its model is loaded. The block geometry is serialized in
     * block space ({@link BlockModelAsset}); textures use the same
     * last-segment resolution as every other pack model.
     */
    private void ensureBlockModelAssets(PackBlockDefinition definition) {
        PackBlockAsset binding = blockAssetsByKey.get(definition.namespace() + ":" + definition.id());
        if (binding == null) {
            return;
        }
        // The geometry attaches under the loaded model key (the model file's
        // stem), not the derived render-time multiblock key — the latter only
        // exists after the first render and would leave the pack artifact
        // without the block model.
        String geometryKey = definition.geometryModelKey();
        VirtualModel model = virtualBlockManager.getModel(geometryKey);
        if (model == null) {
            return;
        }
        String namespace = definition.namespace();
        try {
            assetRegistry.register(new BlockModelAsset(namespace, definition.id(), namespace,
                    geometryKey, virtualBlockManager.getModelSourceFile(geometryKey), model));
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
            return;
        }
        registerModelTextures(namespace, geometryKey, model, "block");
    }

    /**
     * Idempotently derives a bone rig's per-bone item models once its geometry
     * model is loaded, then registers the blueprint with the rig renderer.
     * A model with no bones collapses to a single root bone, so a rig never
     * renders nothing when the source has geometry.
     */
    private void ensureBoneRigAssets(PackBoneRigDefinition definition) {
        if (!rigSettings.enabled()) {
            // The axis is off: keep the artifact byte-identical to a build
            // without bone rigs instead of compiling models nothing uses.
            return;
        }
        VirtualModel model = virtualBlockManager.getModel(definition.geometryModelKey());
        if (model == null) {
            return;
        }
        String namespace = definition.namespace();
        try {
            BoneRigDefinition rig = BoneRigDefinition.fromModel(
                    definition.modelKey(), model, "rig/" + definition.id());
            BoneRigBlueprint blueprint = BoneRigBlueprint.of(definition.modelKey(), namespace, rig);
            List<String> warnings = new java.util.ArrayList<>();
            for (BoneRigAssetWriter.Entry entry
                    : BoneRigAssetWriter.writeRig(model, blueprint, warnings)) {
                registerGeneratedAsset(namespace, entry.path(), entry.bytes());
            }
            for (String warning : warnings) {
                DebugLogger.warning("[Pack] Bone rig '" + definition.contentId() + "': " + warning);
            }
            boneRenderer.registerBlueprint(blueprint);
            registerModelTextures(namespace, definition.geometryModelKey(), model, "item");
        } catch (IllegalArgumentException conflict) {
            plugin.getLogger().warning("[Pack] " + conflict.getMessage());
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("[Pack] Bone rig '" + definition.contentId()
                    + "' could not be derived: " + failure);
        }
    }

    /**
     * Registers the PNG assets of one loaded model under the canonical paths
     * its serialized texture references use. Shared by the item and block axes
     * — the texture pipeline is one pipeline.
     *
     * @param folder {@code item} or {@code block}: the client's atlases only
     *               stitch textures under those directories, so the asset and
     *               the model reference must agree on the prefix
     */
    private void registerModelTextures(String namespace, String modelKey, VirtualModel model, String folder) {
        for (String textureName : model.textureNames()) {
            if (textureName == null || textureName.isBlank()) {
                continue;
            }
            String normalized = TextureAsset.normalizePath(textureName);
            if (normalized.isEmpty()) {
                continue;
            }
            String texturePath = folder + "/" + normalized;
            try {
                File textureFile = virtualBlockManager.resolveTextureFile(modelKey, textureName);
                if (textureFile != null) {
                    assetRegistry.register(new TextureAsset(namespace, texturePath, namespace, textureFile));
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
            for (PackBlockDefinition definition : registeredBlocks) {
                ensureBlockModelAssets(definition);
            }
            for (PackBoneRigDefinition definition : registeredBoneRigs) {
                ensureBoneRigAssets(definition);
            }
            List<PackAsset> snapshot = assetRegistry.snapshot();
            if (snapshot.isEmpty()) {
                DebugLogger.info("[Pack] No assets registered; nothing to compile.");
                return null;
            }
            PackArtifact artifact = compiler.compile(snapshot);
            if (artifact != null) {
                currentArtifact = artifact;
                boolean contentChanged = delivery.publish(artifact);
                if (contentChanged) {
                    plugin.getLogger().info("[Pack] Artifact " + artifact.artifactName() + " ("
                            + artifact.assetCount() + " assets, " + artifact.byteSize() + " bytes) is current"
                            + describeDeliverySuffix() + ".");
                    // Players online before this compile finished (or holding a
                    // previous artifact) get the new pack without a rejoin.
                    // Unchanged recompiles never re-prompt anyone.
                    autoPushToOnlinePlayers();
                }
            }
            return artifact;
        } catch (Throwable failure) {
            plugin.getLogger().warning("[Pack] Compilation failed; previous artifact kept: "
                    + failure.getMessage());
            return null;
        }
    }

    /** Delivery context for the compile log line (endpoint port / static URL). */
    private String describeDeliverySuffix() {
        return switch (settings.deliveryMode()) {
            case LOCAL -> delivery.localPort() > 0
                    ? "; served on port " + delivery.localPort()
                    + (delivery.localPortDrifted() ? " (configured port was busy)" : "")
                    : "; local endpoint unavailable";
            case STATIC_URL -> "; static URL delivery";
            case DISABLED -> "; manual distribution (delivery disabled)";
        };
    }

    /**
     * Pushes the current artifact to online players who have not been
     * prompted yet (join before the first compile) or already accepted a
     * previous pack (artifact changed under them). Declined players are
     * respected; the push runs on the main thread (region-aware).
     */
    private void autoPushToOnlinePlayers() {
        PackScheduling.scheduleNow(plugin, () -> {
            if (!running || currentArtifact == null || !delivery.isDeliverable()) {
                return;
            }
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerPackState state = delivery.state(player.getUniqueId());
                if (state == PlayerPackState.UNKNOWN
                        || state == PlayerPackState.ACCEPTED
                        || state == PlayerPackState.APPLIED) {
                    delivery.deliver(player);
                }
            }
        });
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

    public PackBlockRenderer blockRenderer() {
        return blockRenderer;
    }

    /** The bone-rig renderer ({@code renderKind: "bones"} levels + mob rigs). */
    public BoneRigRenderer boneRenderer() {
        return boneRenderer;
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

    /**
     * The URL one player's client would use to download the current pack
     * (per-player host resolution), or null when nothing is deliverable.
     * Diagnostics surface — never gameplay state.
     */
    public String deliveryUrlFor(Player player) {
        PackArtifact artifact = currentArtifact;
        return artifact == null ? null : delivery.urlFor(artifact, player);
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

    /**
     * Outcome of the self-check that the artifact the server hands out is the
     * artifact it means to hand out.
     *
     * @param reachable  the download URL answered at all
     * @param bytes      body length
     * @param entries    zip entries parsed from the body ({@code -1} when it is not a zip)
     * @param hashMatches the body's SHA-1 equals the artifact's recorded SHA-1
     * @param detail     what was seen, for the command line
     */
    public record DeliveryCheck(boolean reachable, int bytes, int entries, boolean hashMatches, String detail) {

        public boolean ok() {
            return reachable && hashMatches && entries > 0;
        }
    }

    /**
     * Downloads the current artifact through its own delivery URL and checks it
     * end to end: the endpoint answers, the bytes are a readable zip, and their
     * SHA-1 matches what the client was told. A pack that "downloads" but never
     * applies is almost always exactly this — an endpoint serving a truncated,
     * stale, or mismatched file — and it is invisible from the server side
     * until someone asks. Runs off-thread; the returned future completes on the
     * calling thread's executor.
     */
    public CompletableFuture<DeliveryCheck> verifyDeliveryAsync() {
        PackArtifact artifact = currentArtifact;
        if (artifact == null) {
            return CompletableFuture.completedFuture(
                    new DeliveryCheck(false, 0, -1, false, "no artifact compiled"));
        }
        String url = delivery.urlFor(artifact);
        if (url == null) {
            return CompletableFuture.completedFuture(
                    new DeliveryCheck(false, 0, -1, false, "delivery is not reachable (mode DISABLED,"
                            + " endpoint down, or no URL configured)"));
        }
        CompletableFuture<DeliveryCheck> future = new CompletableFuture<>();
        Thread probe = new Thread(() -> future.complete(probeDelivery(url, artifact)), "mineplus-pack-verify");
        probe.setDaemon(true);
        probe.start();
        return future;
    }

    /**
     * {@link #verifyDeliveryAsync()} with the answer delivered on the main
     * thread, so a command can report it without touching Bukkit off-thread.
     */
    public void verifyDelivery(java.util.function.Consumer<DeliveryCheck> callback) {
        verifyDeliveryAsync().thenAccept(check -> PackScheduling.scheduleNow(plugin, () -> callback.accept(check)));
    }

    private static DeliveryCheck probeDelivery(String url, PackArtifact artifact) {
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                return new DeliveryCheck(false, 0, -1, false, "endpoint answered HTTP " + status + " at " + url);
            }
            byte[] body;
            try (java.io.InputStream in = connection.getInputStream()) {
                body = in.readAllBytes();
            } finally {
                connection.disconnect();
            }
            int entryCount = 0;
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(body))) {
                while (zip.getNextEntry() != null) {
                    entryCount++;
                }
            } catch (IOException notAZip) {
                return new DeliveryCheck(true, body.length, -1, false,
                        "body is not a readable zip (" + notAZip.getMessage() + ")");
            }
            String sha1 = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(body));
            boolean matches = sha1.equalsIgnoreCase(artifact.sha1());
            return new DeliveryCheck(true, body.length, entryCount, matches, matches
                    ? "served file matches the current artifact"
                    : "SHA-1 mismatch: served " + sha1 + ", artifact " + artifact.sha1()
                            + " — clients reject this pack");
        } catch (Throwable failure) {
            return new DeliveryCheck(false, 0, -1, false, "probe failed: " + failure);
        }
    }

    public MineplusPlugin plugin() {
        return plugin;
    }
}
