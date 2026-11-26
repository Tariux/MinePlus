package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.virtual.animation.AnimationBinding;
import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker;
import com.mineplus.infrastructure.virtual.texel.TexelSurfacePlan;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import com.mineplus.infrastructure.api.event.VirtualBlockClearAreaEvent;
import com.mineplus.infrastructure.api.event.VirtualBlockInstanceRemoveEvent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VirtualBlockManager implements Listener {

    private static final String DISPLAY_TAG_PREFIX = "mineplus_vblock:";
    private static final Material BARRIER_MATERIAL = Material.BARRIER;
    private static final String MODELS_FOLDER = "models";

    private final Map<String, VirtualModel> loadedModels = new ConcurrentHashMap<>();
    private final Map<String, ModelMeta> modelMeta = new ConcurrentHashMap<>();
    private final Map<BlockCoordinate, UUID> blockToModelMap = new ConcurrentHashMap<>();
    /** Anchor block -> active instance id; spawn-time record used to dedupe restores. */
    private final Map<BlockCoordinate, UUID> anchorToInstance = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new ConcurrentHashMap<>();
    private final GeometryOccupancyCalculator occupancyCalculator = new GeometryOccupancyCalculator();
    private final Map<String, Map<String, TextureMaterialResolver.Resolution>> textureReports = new ConcurrentHashMap<>();
    private final Map<String, TexelBakeResult> texelBakes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TexelBakeResult>> texelBakeFutures = new ConcurrentHashMap<>();
    private final Map<String, File> modelSourceFiles = new ConcurrentHashMap<>();
    /** Bumped whenever bake results are invalidated; async bakes only land if still current. */
    private final AtomicLong texelBakeGeneration = new AtomicLong();
    /** Upper bound for waiting on an in-flight bake when a spawn needs it. */
    private static final long TEXEL_BAKE_JOIN_TIMEOUT_MS = 2_000L;

    /**
     * Dedicated bake pool: settings changes and reloads rebake every model, and PNG
     * decode plus rasterization is heavy — running that on the ForkJoinPool commonPool
     * would spike memory and starve parallel streams/completable stages server-wide.
     */
    private final ExecutorService texelBakeExecutor;

    private JavaPlugin plugin;
    private VirtualRenderingSettings settings = VirtualRenderingSettings.defaults();
    private TexelBakingSettings texelSettings = TexelBakingSettings.defaults();
    private TextureImageStore textureImageStore;
    private MultiBlockLifecycleManager lifecycleManager;
    private DisplayTransport displayTransport;
    private com.mineplus.infrastructure.perf.PerfRegistry perf;

    public VirtualBlockManager() {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger bakeThreadCounter = new AtomicInteger();
        this.texelBakeExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "mineplus-texel-bake-" + bakeThreadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public record ActiveVirtualBlock(
            String modelName,
            Location origin,
            Quaternionf rotation,
            List<UUID> displayEntities,
            Set<Location> barrierBlocks,
            List<AnimationBinding> animationBindings,
            Vector3f pivotCorrection
    ) {
        public ActiveVirtualBlock {
            animationBindings = animationBindings == null ? List.of() : List.copyOf(animationBindings);
            pivotCorrection = pivotCorrection == null ? new Vector3f() : new Vector3f(pivotCorrection);
        }
    }

    public Map<UUID, ActiveVirtualBlock> activeBlocksView() {
        return Collections.unmodifiableMap(activeBlocks);
    }

    public void loadModels(JavaPlugin plugin) {
        this.plugin = plugin;
        loadModelDefinitions();
    }

    public void setDisplayTransport(DisplayTransport transport) {
        this.displayTransport = transport;
    }

    public DisplayTransport displayTransport() {
        return displayTransport;
    }

    /** Telemetry sink for texel bake counters (may be {@code null}). */
    public void setPerfRegistry(com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this.perf = perf;
    }

    public void setLifecycleManager(MultiBlockLifecycleManager manager) {
        this.lifecycleManager = manager;
    }

    public void updateSettings(VirtualRenderingSettings settings) {
        this.settings = settings == null ? VirtualRenderingSettings.defaults() : settings;
        occupancyCalculator.clearCache();
    }

    public void updateTexelSettings(TexelBakingSettings settings) {
        this.texelSettings = settings == null ? TexelBakingSettings.defaults() : settings;
        texelBakes.clear();
        texelBakeFutures.clear();
        texelBakeGeneration.incrementAndGet();
        for (Map.Entry<String, VirtualModel> entry : loadedModels.entrySet()) {
            bakeTexelSurfacesAsync(entry.getKey(), entry.getValue(),
                    modelMeta.get(entry.getKey()), modelSourceFiles.get(entry.getKey()));
        }
    }

    public VirtualRenderingSettings settings() {
        return settings;
    }

    public TexelBakingSettings texelSettings() {
        return texelSettings;
    }

    public void reloadModelDefinitions() {
        loadModelDefinitions();
    }

    public Set<String> getAvailableModels() {
        return Set.copyOf(loadedModels.keySet());
    }

    public VirtualModel getModel(String name) {
        if (name == null || name.isBlank()) return null;
        return loadedModels.get(name.toLowerCase(Locale.ROOT));
    }

    public ModelMeta getModelMeta(String name) {
        if (name == null || name.isBlank()) return ModelMeta.empty();
        return modelMeta.getOrDefault(name.toLowerCase(Locale.ROOT), ModelMeta.empty());
    }

    public Map<String, TextureMaterialResolver.Resolution> getTextureReport(String name) {
        if (name == null || name.isBlank()) return Map.of();
        return textureReports.getOrDefault(name.toLowerCase(Locale.ROOT), Map.of());
    }

    public TexelBakeResult getTexelBake(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        TexelBakeResult baked = texelBakes.get(key);
        if (baked != null) return baked;
        // Bake still in flight (async): wait for it so spawns/restores never
        // render a texel model without its textures. Bounded — on timeout the
        // model renders through the legacy tier rather than blocking forever.
        CompletableFuture<TexelBakeResult> future = texelBakeFutures.get(key);
        if (future == null) return null;
        try {
            return future.get(TEXEL_BAKE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            DebugLogger.warning("[TexelBaking] Waiting for model '" + key + "' bake failed: " + e.getMessage());
            return null;
        }
    }

    public boolean hasTextureImage(String modelName, String textureName) {
        if (textureName == null || textureName.isBlank()) return false;
        String key = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        return imageStore().isResolvable(textureName, modelSourceFiles.get(key));
    }

    public void registerModel(String name, VirtualModel model) {
        registerModel(name, model, ModelMeta.empty());
    }

    public void registerModel(String name, VirtualModel model, ModelMeta meta) {
        registerModel(name, model, meta, null);
    }

    public void registerModel(String name, VirtualModel model, ModelMeta meta, File modelFile) {
        if (name == null || model == null) return;
        String key = name.toLowerCase(Locale.ROOT);
        // The registry key is the single identity every auxiliary state keys on
        // (meta overrides, texel bakes, occupancy cache, animation lookups);
        // align the model's internal name with it so a lookup by model.name()
        // can never miss on a name/key divergence.
        model = model.withName(key);
        loadedModels.put(key, model);
        modelMeta.put(key, meta == null ? ModelMeta.empty() : meta);
        if (modelFile != null) {
            modelSourceFiles.put(key, modelFile);
        } else {
            modelSourceFiles.remove(key);
        }
        bakeTexelSurfacesAsync(key, model, modelMeta.get(key), modelSourceFiles.get(key));
    }

    public GeometryOccupancyCalculator occupancyCalculator() {
        return occupancyCalculator;
    }

    public UUID spawnModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        return spawnModel(model, placement, UUID.randomUUID());
    }

    public enum SpawnAreaResult {
        CLEAR, CLEARED, BLOCKED
    }

    public SpawnAreaResult prepareSpawnArea(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement, boolean clear) {
        SpawnContext context = resolveSpawnContext(model, placement);
        List<Location> clearCandidates = new ArrayList<>();
        for (int i = 0; i + 2 < context.cells().length; i += 3) {
            Location location = context.origin().clone().add(context.cells()[i], context.cells()[i + 1], context.cells()[i + 2]);
            Block block = location.getBlock();
            if (block.getType().isAir()) continue;
            if (!clear) return SpawnAreaResult.BLOCKED;
            UUID owner = blockToModelMap.get(BlockCoordinate.from(location));
            if (owner != null && activeBlocks.containsKey(owner)) {
                continue;
            }
            clearCandidates.add(location);
        }
        if (!clearCandidates.isEmpty()) {
            VirtualBlockClearAreaEvent event = new VirtualBlockClearAreaEvent(
                    UUID.nameUUIDFromBytes(model.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    context.origin().clone(), clearCandidates);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return SpawnAreaResult.BLOCKED;
            }
            for (Location location : event.getCells()) {
                location.getBlock().setType(Material.AIR);
            }
            return SpawnAreaResult.CLEARED;
        }
        return SpawnAreaResult.CLEAR;
    }

    public void removeModel(UUID instanceId) {
        removeModelInternal(instanceId, VirtualBlockInstanceRemoveEvent.Reason.EXPLICIT);
    }

    public void removeAllModels() {
        for (UUID id : new ArrayList<>(activeBlocks.keySet())) {
            removeModelInternal(id, VirtualBlockInstanceRemoveEvent.Reason.CLEAR);
        }
    }

    public void shutdown() {
        removeAllModels();
        if (displayTransport != null) {
            displayTransport.shutdown();
            displayTransport = null;
        }
        texelBakeExecutor.shutdownNow();
    }

    public boolean exists(UUID instanceId) {
        return activeBlocks.containsKey(instanceId);
    }

    public Set<Location> getBarrierLocations(UUID instanceId) {
        ActiveVirtualBlock activeBlock = activeBlocks.get(instanceId);
        return activeBlock == null ? Set.of() : activeBlock.barrierBlocks();
    }

    public ActiveVirtualBlock getVirtualBlockAt(Location location) {
        if (location == null) return null;
        UUID instanceId = blockToModelMap.get(BlockCoordinate.from(location));
        return instanceId != null ? activeBlocks.get(instanceId) : null;
    }

    public UUID getInstanceIdAt(Location location) {
        if (location == null) return null;
        return blockToModelMap.get(BlockCoordinate.from(location));
    }

    @EventHandler
    public void onBarrierBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLatticeBlock(block.getType())) return;

        UUID instanceId = blockToModelMap.get(BlockCoordinate.from(block.getLocation()));
        if (instanceId != null) {
            event.setDropItems(false);
            removeModelInternal(instanceId, VirtualBlockInstanceRemoveEvent.Reason.BLOCK_BREAK);
            if (lifecycleManager != null) {
                MultiBlockInstance instance = lifecycleManager.findByRenderedModelId(instanceId);
                if (instance != null) {
                    lifecycleManager.remove(instance.id(), event.getPlayer(), true);
                }
            }
        }
    }

    private void loadModelDefinitions() {
        loadedModels.clear();
        modelMeta.clear();
        textureReports.clear();
        texelBakes.clear();
        texelBakeFutures.clear();
        texelBakeGeneration.incrementAndGet();
        modelSourceFiles.clear();
        if (textureImageStore != null) {
            textureImageStore.clear();
        }
        occupancyCalculator.clearCache();
        if (plugin == null) return;

        loadExternalModelDefinitions(plugin.getDataFolder());
        DebugLogger.info("Virtual models ready: " + loadedModels.size());
    }

    private void loadExternalModelDefinitions(File pluginFolder) {
        File modelsFolder = new File(pluginFolder, MODELS_FOLDER);
        ModelImportCoordinator coordinator = new ModelImportCoordinator(
                file -> modelKeyFromFile(modelsFolder, file), plugin.getLogger());
        ModelImportCoordinator.LoadResult result = coordinator.importFolder(modelsFolder);

        for (ModelImportCoordinator.ModelEntry entry : result.entries()) {
            VirtualModel model = entry.model();
            ModelMeta meta = ModelMeta.load(entry.file());
            model = mergeDeclaredAnimations(entry, model, meta, modelsFolder);
            model = mergeDisplayAdjustment(entry, model, meta, modelsFolder);
            registerModel(entry.key(), model, meta, entry.file());
            textureReports.put(entry.key(), resolveTextureReport(model));
        }
    }

    /**
     * Applies a dedicated display-adjustment model ({@code "displayFile"} in
     * the meta, or a sibling {@code <stem>.display.bbmodel}) to a model. The
     * adjustment file's authored item display transforms replace the target's
     * display entirely: authors who animate a complicated model in one
     * bbmodel can tune the first-person / hand / GUI pose in a second file
     * with Blockbench's display editor, without touching the animation file.
     */
    private VirtualModel mergeDisplayAdjustment(ModelImportCoordinator.ModelEntry entry,
                                                VirtualModel model, ModelMeta meta, File modelsFolder) {
        File adjustmentFile = resolveDisplayAdjustmentFile(entry, meta, modelsFolder);
        if (adjustmentFile == null) {
            return model;
        }
        VirtualModel parsed = BbModelImporter.parse(
                entry.key() + "#display-adjust", adjustmentFile, plugin.getLogger());
        if (parsed == null || parsed.display() == null || parsed.display().isEmpty()) {
            DebugLogger.warning("[ModelImport] Display adjustment '"
                    + adjustmentFile.getName() + "' for model '" + entry.key()
                    + "' carries no display transforms; ignored.");
            return model;
        }
        DebugLogger.info("[ModelImport] Applied display adjustments from '"
                + adjustmentFile.getName() + "' to model '" + entry.key() + "'.");
        return model.withDisplay(parsed.display());
    }

    /** The meta-declared or sibling display-adjustment file, or null when neither exists. */
    private File resolveDisplayAdjustmentFile(ModelImportCoordinator.ModelEntry entry,
                                              ModelMeta meta, File modelsFolder) {
        String declared = meta == null ? null : meta.displayFile();
        if (declared != null && !declared.isBlank()) {
            if (!hasModelExtension(declared)) {
                declared = declared + ".bbmodel";
            }
            File file = new File(modelsFolder, declared.trim());
            if (!file.isFile()) {
                DebugLogger.warning("[ModelImport] Model '" + entry.key()
                        + "' declares displayFile '" + meta.displayFile()
                        + "' but no such file exists in the models folder.");
                return null;
            }
            return file;
        }
        File modelFile = entry.file();
        if (modelFile == null || modelFile.getParentFile() == null) {
            return null;
        }
        String name = modelFile.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        for (String extension : MODEL_SIDECAR_EXTENSIONS) {
            File sibling = new File(modelFile.getParentFile(), stem + extension);
            if (sibling.isFile()) {
                return sibling;
            }
        }
        return null;
    }

    /** Display sidecar extensions, most specific first. */
    private static final String[] MODEL_SIDECAR_EXTENSIONS = {".display.mpmodel", ".display.bbmodel"};

    /** True when a meta-declared path already names a model source. */
    private static boolean hasModelExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bbmodel") || lower.endsWith(".mpmodel");
    }

    /**
     * Merges a meta-declared animation file ({@code "animationFile": "..."} in
     * {@code <key>.meta.json}) into the model: the file is parsed with the same
     * streaming importer and its clips join the model's own by bone name, later
     * file wins per clip name. Lets an author keep all animation work in one
     * dedicated bbmodel beside the model file.
     */
    private VirtualModel mergeDeclaredAnimations(ModelImportCoordinator.ModelEntry entry,
                                                 VirtualModel model, ModelMeta meta, File modelsFolder) {
        if (meta == null || meta.animationFile() == null || meta.animationFile().isBlank()) {
            return model;
        }
        String declared = meta.animationFile().trim();
        if (!hasModelExtension(declared)) {
            declared = declared + ".bbmodel";
        }
        File animationFile = new File(modelsFolder, declared);
        if (!animationFile.isFile()) {
            DebugLogger.warning("[ModelImport] Model '" + entry.key()
                    + "' declares animationFile '" + meta.animationFile()
                    + "' but no such file exists in the models folder.");
            return model;
        }
        VirtualModel parsed = BbModelImporter.parse(entry.key() + "#declared-anim", animationFile, plugin.getLogger());
        if (parsed == null || parsed.animations().isEmpty()) {
            return model;
        }
        List<AnimationClip> clips = parsed.animations();
        DebugLogger.info("[ModelImport] Merged " + clips.size() + " clip(s) from '"
                + declared + "' into model '" + entry.key() + "'.");
        return model.withAnimations(clips);
    }

    /**
     * Bakes texel surfaces off the main thread on the dedicated bake pool. The
     * result only lands if no reload/settings change invalidated bakes while it
     * was running; spawn paths waiting on the future always receive their own
     * result regardless. Completion handling runs {@code whenComplete} so an
     * exceptional bake still drops its future — otherwise every later
     * {@link #getTexelBake} would block on the join timeout forever.
     */
    private void bakeTexelSurfacesAsync(String key, VirtualModel model, ModelMeta meta, File modelFile) {
        TexelBakingSettings settingsSnapshot = texelSettings;
        TextureImageStore store = imageStore();
        long generation = texelBakeGeneration.get();
        com.mineplus.infrastructure.perf.PerfRegistry registry = perf;
        if (registry != null) {
            registry.add(com.mineplus.infrastructure.perf.PerfRegistry.Counter.TEXEL_BAKES_QUEUED, 1);
        }
        CompletableFuture<TexelBakeResult> future = CompletableFuture.supplyAsync(() -> {
            // Skip superseded work before paying for PNG decode + rasterization:
            // a reload or settings change has already scheduled a fresher bake
            // for this key (and replaced the map entry waiters look at). The
            // cheap disabled result only completes this stale future.
            if (texelBakeGeneration.get() != generation) {
                return TexelBakeResult.disabled(
                        settingsSnapshot.effectiveMode(meta),
                        settingsSnapshot.effectiveDetail(meta),
                        settingsSnapshot,
                        model == null ? 0 : model.cubes().size());
            }
            return TexelSurfaceBaker.bakeModel(model, meta, modelFile, store, settingsSnapshot);
        }, texelBakeExecutor);
        texelBakeFutures.put(key, future);
        future.whenComplete((result, error) -> {
            texelBakeFutures.remove(key, future);
            if (error != null) {
                if (registry != null) {
                    registry.add(com.mineplus.infrastructure.perf.PerfRegistry.Counter.TEXEL_BAKES_FAILED, 1);
                }
                DebugLogger.warning("[TexelBaking] Model '" + key + "' bake failed: "
                        + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
                return;
            }
            if (registry != null) {
                registry.add(com.mineplus.infrastructure.perf.PerfRegistry.Counter.TEXEL_BAKES_COMPLETED, 1);
            }
            if (texelBakeGeneration.get() != generation) return; // superseded by a reload
            texelBakes.put(key, result);
            if (result.enabled() && result.facesBaked() > 0) {
                DebugLogger.info("[TexelBaking] Model '" + key + "': baked async " + result.facesBaked()
                        + "/" + result.facesTotal() + " face(s) into " + result.totalPlates()
                        + " merged plate(s) in " + (result.bakeTimeNanos() / 1_000_000.0) + " ms.");
            }
        });
    }

    private synchronized TextureImageStore imageStore() {
        if (textureImageStore == null) {
            File root = plugin != null ? new File(plugin.getDataFolder(), MODELS_FOLDER) : null;
            textureImageStore = new TextureImageStore(root);
        }
        return textureImageStore;
    }

    private Map<String, TextureMaterialResolver.Resolution> resolveTextureReport(VirtualModel model) {
        Map<String, TextureMaterialResolver.Resolution> report = new LinkedHashMap<>();
        for (String texture : model.textureNames()) {
            report.put(texture, TextureMaterialResolver.resolveDetailed(texture));
        }
        return report;
    }

    private String modelKeyFromFile(File baseFolder, File file) {
        String basePath = baseFolder.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        String relative = filePath.startsWith(basePath) ? filePath.substring(basePath.length()) : file.getName();
        relative = relative.replace('\\', '/');
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return stripExtension(relative).toLowerCase(Locale.ROOT);
    }

    private record SpawnContext(
            ModelMeta.CollisionMode collisionMode,
            ModelMeta.OriginMode originMode,
            Location origin,
            RotationSnapper.SnappedRotation snapped,
            Quaternionf globalRotation,
            int[] cells
    ) {}

    private SpawnContext resolveSpawnContext(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        ModelMeta meta = getModelMeta(model.name());
        ModelMeta.CollisionMode collisionMode = meta.collisionMode() != null ? meta.collisionMode() : settings.collisionMode();
        ModelMeta.OriginMode originMode = effectiveOriginMode(model);

        Location origin = new Location(
                placement.location().getWorld(),
                placement.location().getBlockX(),
                placement.location().getBlockY(),
                placement.location().getBlockZ()
        );

        RotationSnapper.SnappedRotation snapped = settings.rotationSnap()
                ? RotationSnapper.snap(placement.globalRotation(), settings.rotationSnapThresholdDegrees())
                : null;
        Quaternionf globalRotation = snapped != null ? snapped.quaternion() : new Quaternionf(placement.globalRotation());

        if (collisionMode == ModelMeta.CollisionMode.SINGLE) {
            // Exactly one solid block at the anchor (the axle cell): the model's
            // whole filling space. Nothing else in the world is ever touched.
            return new SpawnContext(collisionMode, originMode, origin, snapped,
                    globalRotation, new int[] {0, 0, 0});
        }

        int[] cells = occupancyCalculator.compute(
                model, snapped, placement.globalRotation(), collisionMode,
                settings.collisionEpsilon(), originMode);
        // A declared filling space limits which cells may ever become solid —
        // everything outside it stays world space (flowing water, open air).
        // When the geometry has no cell inside the declared space, the space
        // itself is the collision volume: the model declares what it fills.
        cells = meta == null ? cells : meta.restrictCells(cells);
        if (meta != null && meta.fillBox() != null && cells.length == 0) {
            cells = ModelMeta.fillBoxCells(meta.fillBox());
        }

        return new SpawnContext(collisionMode, originMode, origin, snapped, globalRotation, cells);
    }

    private UUID spawnModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement, UUID instanceId) {
        return spawnInternal(model, placement, instanceId, true);
    }

    /**
     * Spawns the collision lattice for a model whose visuals render through
     * another backend (the pack axis): barriers, occupancy, break handling,
     * ghost cleanup and restore dedupe behave exactly like a full virtual
     * spawn — only display entities are not emitted. The returned id owns the
     * barrier map entries and the {@code ActiveVirtualBlock} record, so the
     * calling backend keys its own visuals by it.
     */
    public UUID spawnCollisionModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        return spawnInternal(model, placement, UUID.randomUUID(), false);
    }

    /** An anchored model instance: the Core instance id plus the Interaction entity that hooks clicks. */
    public record AnchoredModel(UUID instanceId, org.bukkit.entity.Interaction interaction) {}

    /**
     * Spawns a model anchored per its meta {@code anchorMode}:
     *
     * <ul>
     *   <li>{@link AnchorMode#LATTICE} — identical to {@link #spawnModel}.</li>
     *   <li>{@link AnchorMode#FRAME} — no barrier blocks; one invisible
     *       {@code Interaction} entity sized from the model's bounding box
     *       (clamped inside the anchor block) provides the click hooks.</li>
     *   <li>{@link AnchorMode#INTERACTION_ONLY} — like FRAME but with a
     *       full-block hitbox for easier clicking.</li>
     * </ul>
     *
     * <p>The instance is registered in the same books as any other spawn, so
     * {@link #removeModel}, area clears, chunk restore and
     * {@link com.mineplus.infrastructure.api.event.VirtualBlockInstanceRemoveEvent}
     * all work unchanged. Mount an external visual (a pack ItemDisplay) as a
     * passenger of the returned interaction entity and it can never drift from
     * the anchor — removing the instance removes the anchor, the passenger and
     * the hooks together.
     *
     * @return null when the anchor block is occupied (mirrors the lattice
     *         STRICT policy), otherwise the instance ids.
     */
    public AnchoredModel spawnAnchoredModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        ModelMeta meta = getModelMeta(model.name());
        AnchorMode mode = meta == null ? AnchorMode.LATTICE : meta.resolvedAnchorMode();
        if (mode == AnchorMode.LATTICE) {
            UUID id = spawnModel(model, placement);
            return id == null ? null : new AnchoredModel(id, null);
        }

        SpawnContext context = resolveSpawnContext(model, placement);
        Location origin = context.origin();
        Block anchorBlock = origin.getBlock();
        if (!anchorBlock.getType().isAir()
                && settings.collisionNonAirPolicy() == VirtualRenderingSettings.NonAirPolicy.STRICT) {
            DebugLogger.warning("spawnAnchoredModel: anchor " + origin + " is not air; STRICT policy aborted spawn of '" + model.name() + "'.");
            return null;
        }

        UUID instanceId = UUID.randomUUID();
        org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction)
                origin.getWorld().spawnEntity(origin.clone().add(0.5, 0.0, 0.5), org.bukkit.entity.EntityType.INTERACTION);
        interaction.setResponsive(true);

        if (mode == AnchorMode.FRAME) {
            float[] half = modelHalfExtents(model);
            // Clamp inside the anchor block so a frame-anchored item never
            // claims more than its 1x1x1 cell, even for slightly loose models.
            float width = Math.min(2f * Math.max(half[0], half[2]), 1.0f);
            float height = Math.min(2f * half[1], 1.0f);
            interaction.setInteractionWidth(Math.max(width, 0.1f));
            interaction.setInteractionHeight(Math.max(height, 0.1f));
        } else {
            interaction.setInteractionWidth(1.0f);
            interaction.setInteractionHeight(1.0f);
        }

        // Register with empty barrier/display sets: chunk load, clear and
        // remove paths all behave, and the remove event fires for backends.
        activeBlocks.put(instanceId, new ActiveVirtualBlock(
                model.name(),
                origin,
                new Quaternionf(context.globalRotation()),
                List.of(),
                Set.of(),
                List.of(),
                null
        ));
        anchorToInstance.put(BlockCoordinate.from(origin), instanceId);
        return new AnchoredModel(instanceId, interaction);
    }

    /** Half extents (x, y, z) of the model's raw cube geometry, in blocks. */
    private float[] modelHalfExtents(VirtualModel model) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedCube cube : model.cubes()) {
            ElementGeometry g = cube.geometry();
            if (g == null) {
                continue;
            }
            Vector3f from = g.from();
            Vector3f to = g.to();
            minX = Math.min(minX, from.x); minY = Math.min(minY, from.y); minZ = Math.min(minZ, from.z);
            maxX = Math.max(maxX, to.x); maxY = Math.max(maxY, to.y); maxZ = Math.max(maxZ, to.z);
        }
        if (minX > maxX) {
            return new float[]{0.5f, 0.5f, 0.5f};
        }
        return new float[]{(maxX - minX) / 2f, (maxY - minY) / 2f, (maxZ - minZ) / 2f};
    }

    /** Where a pack-rendered visual for this placement should sit: the anchor origin and the resolved rotation. */
    public record DisplayAnchor(Location origin, Quaternionf rotation, ModelMeta.OriginMode originMode) {}

    /**
     * Resolves the display anchor exactly the way {@link #spawnModel} does for
     * its own display entities — meta origin mode (anchor block corner for
     * GRID, block center for CENTER) and rotation snapping included — so an
     * external rendering backend lands on the same spot as its collision
     * lattice instead of recomputing the anchor by hand.
     */
    public DisplayAnchor displayAnchorFor(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        SpawnContext context = resolveSpawnContext(model, placement);
        Location displayOrigin = context.originMode() == ModelMeta.OriginMode.GRID
                ? context.origin().clone()
                : context.origin().clone().add(0.5, 0.0, 0.5);
        return new DisplayAnchor(displayOrigin, new Quaternionf(context.globalRotation()), context.originMode());
    }

    private UUID spawnInternal(
            VirtualModel model,
            VirtualBlockPlacementHelper.PlacementData placement,
            UUID instanceId,
            boolean withDisplays
    ) {
        SpawnContext context = resolveSpawnContext(model, placement);
        ModelMeta.OriginMode originMode = context.originMode();
        Location origin = context.origin();
        Quaternionf globalRotation = context.globalRotation();
        int[] cells = context.cells();

        Location displayOrigin = originMode == ModelMeta.OriginMode.GRID ? origin.clone() : origin.clone().add(0.5, 0.0, 0.5);
        ModelMeta spawnMeta = getModelMeta(model.name());
        ModelMeta.CollisionMaterial latticeMaterial = spawnMeta != null && spawnMeta.collisionMaterial() != null
                ? spawnMeta.collisionMaterial() : ModelMeta.CollisionMaterial.BARRIER;

        List<UUID> spawnedEntities = new ArrayList<>();
        Set<Location> barrierBlocks = new HashSet<>();
        LightTier lightTier = spawnMeta == null ? LightTier.OFF
                : spawnMeta.light() == null ? LightTier.OFF : spawnMeta.light();
        int lightBudget = lightTier.maxCells();

        for (int i = 0; i + 2 < cells.length; i += 3) {
            Location location = origin.clone().add(cells[i], cells[i + 1], cells[i + 2]);
            Block block = location.getBlock();
            if (block.getType() == Material.WATER) {
                // Never solidify water: machines placed in (or beside) a river
                // keep the flow, and the lattice simply omits that cell.
                continue;
            }
            if (block.getType().isAir()) {
                block.setType(latticeMaterial.block);
                barrierBlocks.add(location);
                blockToModelMap.put(BlockCoordinate.from(location), instanceId);
            } else if (settings.collisionNonAirPolicy() == VirtualRenderingSettings.NonAirPolicy.STRICT) {
                rollbackSpawn(barrierBlocks);
                DebugLogger.warning("spawnModel: collision cell " + location + " is not air; STRICT policy aborted spawn of '" + model.name() + "'.");
                return null;
            }
        }

        // Light engine: the same occupancy cells, re-read as emitters. Placing the
        // light blocks after the lattice means light cells also block movement —
        // a lit lamp is solid, which is what players expect from a machine.
        if (lightTier != LightTier.OFF) {
            java.util.List<Block> lightCells = new java.util.ArrayList<>();
            for (Location placed : barrierBlocks) {
                if (lightBudget <= 0) {
                    break;
                }
                Block b = placed.getBlock();
                if (b.getType() == latticeMaterial.block) {
                    lightCells.add(b);
                    lightBudget--;
                }
            }
            lightTier.placeBlocks(lightCells);
        }

        boolean animated = false;
        List<AnimationBinding> animationBindings = null;
        Vector3f pivotCorrection = null;

        if (withDisplays) {
            Vector3f pivotOffset = new Vector3f(
                    0.5f - (float) (displayOrigin.getX() - origin.getX()),
                    0.5f,
                    0.5f - (float) (displayOrigin.getZ() - origin.getZ()));
            Vector3f rotatedPivotOffset = new Vector3f(pivotOffset);
            globalRotation.transform(rotatedPivotOffset);

            animated = model.hasAnimations();
            animationBindings = animated ? new ArrayList<>() : null;
            TexelBakeResult texelBake = getTexelBake(model.name());
            List<Map<CubeFace, TexelSurfacePlan>> texelCubePlans = texelBake != null && texelBake.enabled() ? texelBake.cubePlans() : null;
            int brightnessFloor = spawnMeta.texelBrightness() != null && texelCubePlans != null ? spawnMeta.texelBrightness() : 0;
            int cubeIndex = 0;
            for (BakedCube cube : model.cubes()) {
                Map<CubeFace, TexelSurfacePlan> facePlans = texelCubePlans != null && cubeIndex < texelCubePlans.size()
                        ? texelCubePlans.get(cubeIndex) : null;
                Map<CubeFace, Integer> fallbackTints = texelBake != null ? texelBake.fallbackTints(cubeIndex) : null;
                for (DisplayEmitter.EmittedDisplay item : DisplayEmitter.emitCube(
                        cube, settings.perFaceRendering(), facePlans, model.resolution(), fallbackTints)) {
                    UUID displayId = spawnDisplayEntity(displayOrigin, instanceId, item, brightnessFloor,
                            globalRotation, pivotOffset, rotatedPivotOffset);
                    spawnedEntities.add(displayId);

                    if (animated && cube.boneIndex() >= 0) {
                        animationBindings.add(new AnimationBinding(
                                cube.boneIndex(),
                                displayId,
                                new Matrix4f()
                                        .translate(item.translation())
                                        .rotate(item.leftRotation())
                                        .scale(item.scale())
                                        .rotate(item.rightRotation())
                        ));
                    }
                }
                cubeIndex++;
            }

            if (animated && !animationBindings.isEmpty()) {
                pivotCorrection = new Vector3f(pivotOffset).sub(rotatedPivotOffset);
            }
        }

        activeBlocks.put(instanceId, new ActiveVirtualBlock(
                model.name(),
                origin,
                globalRotation,
                spawnedEntities,
                barrierBlocks,
                animationBindings == null ? List.of() : animationBindings,
                pivotCorrection
        ));
        anchorToInstance.put(BlockCoordinate.from(origin), instanceId);

        if (withDisplays && displayTransport != null && displayTransport.isRunning()) {
            displayTransport.finishInstance(instanceId, displayOrigin, animated);
        }
        return instanceId;
    }

    private static final Display.Brightness[] BRIGHTNESS_BY_LEVEL = new Display.Brightness[16];

    static {
        for (int level = 0; level < BRIGHTNESS_BY_LEVEL.length; level++) {
            BRIGHTNESS_BY_LEVEL[level] = new Display.Brightness(level, 15);
        }
    }

    private UUID spawnDisplayEntity(
            Location displayOrigin, UUID instanceId, DisplayEmitter.EmittedDisplay item, int brightnessFloor,
            Quaternionf globalRotation, Vector3f pivotOffset, Vector3f rotatedPivotOffset) {
        if (displayTransport != null && displayTransport.isRunning()) {
            return spawnPooledDisplayEntity(displayOrigin, instanceId, item, brightnessFloor,
                    globalRotation, pivotOffset, rotatedPivotOffset);
        }

        BlockDisplay display = (BlockDisplay) displayOrigin.getWorld().spawnEntity(displayOrigin, EntityType.BLOCK_DISPLAY);
        display.setBlock(item.blockData());
        display.addScoreboardTag(DISPLAY_TAG_PREFIX + instanceId);
        int emission = Math.max(item.lightEmission(), brightnessFloor);
        if (emission > 0) {
            display.setBrightness(BRIGHTNESS_BY_LEVEL[Math.min(emission, 15)]);
        }

        Vector3f translation = new Vector3f(item.translation());
        globalRotation.transform(translation);
        translation.add(pivotOffset).sub(rotatedPivotOffset);

        display.setTransformation(new org.bukkit.util.Transformation(
                translation,
                new Quaternionf(globalRotation).mul(item.leftRotation()),
                item.scale(),
                item.rightRotation()
        ));
        return display.getUniqueId();
    }

    private UUID spawnPooledDisplayEntity(
            Location displayOrigin, UUID instanceId, DisplayEmitter.EmittedDisplay item, int brightnessFloor,
            Quaternionf globalRotation, Vector3f pivotOffset, Vector3f rotatedPivotOffset) {
        PooledDisplay pooled = displayTransport.beginInstance(instanceId, displayOrigin::getWorld);
        BlockDisplay display = pooled.asBlockDisplay();
        display.setBlock(item.blockData());
        display.addScoreboardTag(DISPLAY_TAG_PREFIX + instanceId);
        int emission = Math.max(item.lightEmission(), brightnessFloor);
        if (emission > 0) {
            display.setBrightness(BRIGHTNESS_BY_LEVEL[Math.min(emission, 15)]);
        }

        Vector3f translation = new Vector3f(item.translation());
        globalRotation.transform(translation);
        translation.add(pivotOffset).sub(rotatedPivotOffset);

        pooled.moveTo(displayTransport.nms(), displayOrigin.getX(), displayOrigin.getY(), displayOrigin.getZ(), 0f, 0f);
        pooled.setTransform(new Matrix4f()
                .translate(translation)
                .rotate(new Quaternionf(globalRotation).mul(item.leftRotation()))
                .scale(item.scale())
                .rotate(item.rightRotation()), 0);
        return display.getUniqueId();
    }

    /** The origin mode actually used for spawns: explicit meta wins, then settings, then the geometry heuristic. */
    public ModelMeta.OriginMode effectiveOriginMode(VirtualModel model) {
        ModelMeta meta = getModelMeta(model.name());
        ModelMeta.OriginMode originMode = meta.originMode() != null ? meta.originMode() : settings.originMode();
        if (originMode == null || originMode == ModelMeta.OriginMode.AUTO) {
            originMode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());
        }
        return originMode;
    }

    /** {@code true} when a world block could be one of our lattice cells. */
    private static boolean isLatticeBlock(Material type) {
        for (ModelMeta.CollisionMaterial material : ModelMeta.CollisionMaterial.values()) {
            if (material.block == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reverts lattice cells placed by an aborted spawn. Displays never spawn before
     * the lattice passes its checks, so there is nothing else to undo.
     */
    private void rollbackSpawn(Set<Location> barrierBlocks) {
        for (Location loc : barrierBlocks) {
            if (isLatticeBlock(loc.getBlock().getType())) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(BlockCoordinate.from(loc));
        }
    }

    public void cleanupGhostEntities(UUID instanceId) {
        String tag = DISPLAY_TAG_PREFIX + instanceId;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(Display.class)) {
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                }
            }
        }
    }

    public int sweepGhostDisplays() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            // Display (not BlockDisplay): the sweep is tag-driven, so it also
            // covers any display type the render pipeline spawned.
            for (Entity entity : world.getEntitiesByClass(Display.class)) {
                String instanceTag = null;
                for (String tag : entity.getScoreboardTags()) {
                    if (tag.startsWith(DISPLAY_TAG_PREFIX)) {
                        instanceTag = tag;
                        break;
                    }
                }
                if (instanceTag == null) continue;
                try {
                    UUID instanceId = UUID.fromString(instanceTag.substring(DISPLAY_TAG_PREFIX.length()));
                    if (activeBlocks.containsKey(instanceId)) continue;
                    if (lifecycleManager != null && lifecycleManager.registry().getInstance(instanceId) != null) continue;
                    entity.remove();
                    removed++;
                } catch (IllegalArgumentException ignored) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            DebugLogger.info("sweepGhostDisplays: removed " + removed + " stale display entities from loaded chunks.");
        }
        return removed;
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (displayTransport != null && displayTransport.isRunning()) {
            displayTransport.handleChunkLoad(event.getChunk());
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Display)) continue;
            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith(DISPLAY_TAG_PREFIX)) {
                    try {
                        UUID instanceId = UUID.fromString(tag.substring(DISPLAY_TAG_PREFIX.length()));
                        if (!activeBlocks.containsKey(instanceId)) {
                            if (lifecycleManager != null && lifecycleManager.registry().getInstance(instanceId) != null) continue;
                            entity.remove();
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    @EventHandler
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        if (displayTransport != null && displayTransport.isRunning()) {
            displayTransport.handleChunkUnload(event.getChunk());
        }
    }

    private void removeModelInternal(UUID instanceId, VirtualBlockInstanceRemoveEvent.Reason reason) {
        ActiveVirtualBlock activeBlock = activeBlocks.remove(instanceId);
        if (activeBlock == null) return;

        // Conditional remove: the spawn origin is the anchor key, and a newer
        // render may already own the same anchor (remove + respawn swap).
        anchorToInstance.remove(BlockCoordinate.from(activeBlock.origin()), instanceId);

        for (Location loc : activeBlock.barrierBlocks()) {
            if (isLatticeBlock(loc.getBlock().getType())) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(BlockCoordinate.from(loc));
        }

        if (displayTransport != null && displayTransport.isRunning()) {
            displayTransport.removeInstance(instanceId);
        } else {
            for (UUID displayId : activeBlock.displayEntities()) {
                Entity display = Bukkit.getEntity(displayId);
                if (display != null) {
                    display.remove();
                }
            }
        }

        // Notify external backends after the instance is fully torn down, so a
        // listener that respawns sees a clean slate and one that only cleans up
        // (a pack display mounted via spawnAnchoredModel) can never orphan.
        VirtualBlockInstanceRemoveEvent removeEvent =
                new VirtualBlockInstanceRemoveEvent(instanceId, activeBlock.modelName(), activeBlock.origin(), reason);
        Bukkit.getPluginManager().callEvent(removeEvent);
    }

    public UUID restoreForState(BlockCoordinate anchor, String modelKey, Quaternionf rotation) {
        // Dedupe against every active render, not just renders whose collision
        // cells happen to cover the anchor: a model whose geometry does not
        // include the anchor block would slip past the blockToModelMap check
        // and render twice on repeated reconciles.
        UUID alreadyRendered = alreadyRenderedAt(anchor);
        if (alreadyRendered != null) {
            return alreadyRendered;
        }
        VirtualModel model = getModel(modelKey);
        if (model == null) {
            DebugLogger.warning("Cannot restore virtual block: unknown model key '" + modelKey + "' at " + anchor + ".");
            return null;
        }
        World world = Bukkit.getWorld(anchor.worldName());
        if (world == null) {
            DebugLogger.info("restoreForState: World '" + anchor.worldName() + "' not loaded for model key '" + modelKey + "'.");
            return null;
        }
        Location origin = new Location(world, anchor.x(), anchor.y(), anchor.z());
        VirtualBlockPlacementHelper.PlacementData placement = new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        UUID instanceId = spawnModel(model, placement, UUID.randomUUID());
        if (instanceId != null) {
            DebugLogger.info("restoreForState: Spawned virtual block for model key '" + modelKey + "' at " + anchor + " (instanceId=" + instanceId + ").");
        }
        return instanceId;
    }

    /**
     * Restore path for pack-rendered world objects: same dedupe and world
     * guards as {@link #restoreForState}, but spawns only the collision
     * lattice — the caller (backend selection in {@code ModelRenderingManager})
     * attaches its own visuals to the returned id.
     */
    public UUID restoreCollisionForState(BlockCoordinate anchor, String modelKey, Quaternionf rotation) {
        UUID alreadyRendered = alreadyRenderedAt(anchor);
        if (alreadyRendered != null) {
            return alreadyRendered;
        }
        VirtualModel model = getModel(modelKey);
        if (model == null) {
            DebugLogger.warning("Cannot restore collision: unknown model key '" + modelKey + "' at " + anchor + ".");
            return null;
        }
        World world = Bukkit.getWorld(anchor.worldName());
        if (world == null) {
            DebugLogger.info("restoreCollisionForState: World '" + anchor.worldName() + "' not loaded for model key '" + modelKey + "'.");
            return null;
        }
        Location origin = new Location(world, anchor.x(), anchor.y(), anchor.z());
        VirtualBlockPlacementHelper.PlacementData placement = new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        UUID instanceId = spawnCollisionModel(model, placement);
        if (instanceId != null) {
            DebugLogger.info("restoreCollisionForState: Spawned collision lattice for model key '" + modelKey + "' at " + anchor + " (instanceId=" + instanceId + ").");
        }
        return instanceId;
    }

    private UUID alreadyRenderedAt(BlockCoordinate anchor) {
        UUID alreadyRendered = anchorToInstance.get(anchor);
        if (alreadyRendered == null) {
            alreadyRendered = blockToModelMap.get(anchor);
        }
        return alreadyRendered;
    }

    /** Source file of a registered model (pack asset discovery), or null. */
    public File getModelSourceFile(String name) {
        return name == null ? null : modelSourceFiles.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the PNG file for one texture of a registered model through the
     * shared image store (adjacent-then-root, the same resolution the texel
     * baker uses), or null. Pure lookup — no decode.
     */
    public File resolveTextureFile(String modelKey, String textureName) {
        return imageStore().resolveTextureFile(textureName, getModelSourceFile(modelKey));
    }

    /**
     * Resolves a PNG by name against the models root only (no model context),
     * for content that has no bbmodel of its own — an inherited pack item's
     * {@code layer0} texture, for example.
     */
    public File resolveStandaloneTextureFile(String textureName) {
        return imageStore().resolveTextureFile(textureName, null);
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}