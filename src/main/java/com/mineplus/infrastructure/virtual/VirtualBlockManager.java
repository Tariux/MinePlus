// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.virtual.animation.AnimationBinding;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelPalette;
import com.mineplus.infrastructure.virtual.texel.TexelSurfaceBaker;
import com.mineplus.infrastructure.virtual.texel.TexelSurfacePlan;
import com.mineplus.infrastructure.virtual.texel.TextureImageStore;
import com.mineplus.infrastructure.virtual.voxel.VoxelModelBake;
import com.mineplus.infrastructure.virtual.voxel.VoxelRenderingSettings;
import com.mineplus.infrastructure.virtual.voxel.VoxelSurfaceBaker;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
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

    private final Map<String, VirtualModel> loadedModels = new HashMap<>();
    private final Map<String, ModelMeta> modelMeta = new HashMap<>();
    private final Map<BlockCoordinate, UUID> blockToModelMap = new HashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new HashMap<>();
    private final VoxelOccupancyCalculator occupancyCalculator = new VoxelOccupancyCalculator();
    private final Map<String, Map<String, TextureMaterialResolver.Resolution>> textureReports = new ConcurrentHashMap<>();
    private final Map<String, TexelBakeResult> texelBakes = new HashMap<>();
    private final Map<String, VoxelModelBake> voxelBakes = new HashMap<>();
    private final Map<String, File> modelSourceFiles = new HashMap<>();

    private JavaPlugin plugin;
    private VirtualRenderingSettings settings = VirtualRenderingSettings.defaults();
    private TexelBakingSettings texelSettings = TexelBakingSettings.defaults();
    private VoxelRenderingSettings voxelSettings = VoxelRenderingSettings.defaults();
    private TextureImageStore textureImageStore;
    private MultiBlockLifecycleManager lifecycleManager;
    private DisplayTransport displayTransport;

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

    /**
     * Live view of every spawned virtual block, keyed by rendered model id.
     * The animation runtime drives off this map: new spawns appear here (any
     * placement path) and removals drop out, so animation controllers attach
     * and clean up without lifecycle wiring.
     */
    public Map<UUID, ActiveVirtualBlock> activeBlocksView() {
        return Collections.unmodifiableMap(activeBlocks);
    }

    public void loadModels(JavaPlugin plugin) {
        this.plugin = plugin;
        loadModelDefinitions();
    }

    /**
     * Attaches the packet-based display transport. When present, spawns create
     * pooled never-spawned displays streamed per viewer (bundled, LOD-gated);
     * when {@code null}, the legacy spawned-entity path runs unchanged.
     */
    public void setDisplayTransport(DisplayTransport transport) {
        this.displayTransport = transport;
    }

    public DisplayTransport displayTransport() {
        return displayTransport;
    }

    public void setLifecycleManager(MultiBlockLifecycleManager manager) {
        this.lifecycleManager = manager;
    }

    public void updateSettings(VirtualRenderingSettings settings) {
        this.settings = settings == null ? VirtualRenderingSettings.defaults() : settings;
        occupancyCalculator.clearCache();
        // The voxel lattice depends on the origin mode, so a settings change can
        // invalidate cached voxel plans (barrier cells recompute per spawn).
        rebakeVoxelPlans();
    }

    /**
     * Applies global texel baking settings and re-bakes every loaded model with them
     * (decoded PNGs are retained — only the plans depend on the settings). A full
     * model reload re-bakes through {@link #registerModel} anyway.
     */
    public void updateTexelSettings(TexelBakingSettings settings) {
        this.texelSettings = settings == null ? TexelBakingSettings.defaults() : settings;
        texelBakes.clear();
        for (Map.Entry<String, VirtualModel> entry : loadedModels.entrySet()) {
            bakeTexelSurfaces(entry.getKey(), entry.getValue(),
                    modelMeta.get(entry.getKey()), modelSourceFiles.get(entry.getKey()));
        }
        // Texel bake results feed the voxel strategy selection's legacy reporting.
        rebakeVoxelPlans();
    }

    /**
     * Applies global voxel rendering settings and re-bakes every loaded model's
     * voxel reconstruction with them.
     */
    public void updateVoxelSettings(VoxelRenderingSettings settings) {
        this.voxelSettings = settings == null ? VoxelRenderingSettings.defaults() : settings;
        rebakeVoxelPlans();
    }

    private void rebakeVoxelPlans() {
        voxelBakes.clear();
        for (Map.Entry<String, VirtualModel> entry : loadedModels.entrySet()) {
            bakeVoxelPlan(entry.getKey(), entry.getValue(),
                    modelMeta.get(entry.getKey()), modelSourceFiles.get(entry.getKey()));
        }
    }

    public VirtualRenderingSettings settings() {
        return settings;
    }

    public TexelBakingSettings texelSettings() {
        return texelSettings;
    }

    public VoxelRenderingSettings voxelSettings() {
        return voxelSettings;
    }

    public void reloadModelDefinitions() {
        loadModelDefinitions();
    }

    public Set<String> getAvailableModels() {
        return Collections.unmodifiableSet(loadedModels.keySet());
    }

    public VirtualModel getModel(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return loadedModels.get(name.toLowerCase(Locale.ROOT));
    }

    public ModelMeta getModelMeta(String name) {
        if (name == null || name.isBlank()) {
            return ModelMeta.empty();
        }
        return modelMeta.getOrDefault(name.toLowerCase(Locale.ROOT), ModelMeta.empty());
    }

    /** Deterministic per-model texture-resolution report (keyed by texture name). */
    public Map<String, TextureMaterialResolver.Resolution> getTextureReport(String name) {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        return textureReports.getOrDefault(name.toLowerCase(Locale.ROOT), Map.of());
    }

    /**
     * Texel surface bake result for a model (grid/plate/palette diagnostics and the
     * per-face plans consumed at spawn time), or {@code null} when unknown.
     */
    public TexelBakeResult getTexelBake(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return texelBakes.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Voxel reconstruction bake for a model (rendering strategy decision, merged
     * display runs, and diagnostics), or {@code null} when unknown. A bake whose
     * {@link VoxelModelBake#voxelRender()} is false keeps the legacy pipeline.
     */
    public VoxelModelBake getVoxelBake(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return voxelBakes.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a decodable PNG exists for a texture name used by a model — the
     * load-bearing precondition for texel rendering fidelity on that face.
     */
    public boolean hasTextureImage(String modelName, String textureName) {
        if (textureName == null || textureName.isBlank()) {
            return false;
        }
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
        if (name == null || model == null) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        loadedModels.put(key, model);
        modelMeta.put(key, meta == null ? ModelMeta.empty() : meta);
        if (modelFile != null) {
            modelSourceFiles.put(key, modelFile);
        } else {
            modelSourceFiles.remove(key);
        }
        bakeTexelSurfaces(key, model, modelMeta.get(key), modelSourceFiles.get(key));
        bakeVoxelPlan(key, model, modelMeta.get(key), modelSourceFiles.get(key));
    }

    public VoxelOccupancyCalculator occupancyCalculator() {
        return occupancyCalculator;
    }

    public UUID spawnModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        return spawnModel(model, placement, UUID.randomUUID());
    }

    /** Result of a spawn-area inspection/clearing pass. */
    public enum SpawnAreaResult {
        /** Every collision cell is air; nothing had to change. */
        CLEAR,
        /** Non-air blocks occupied cells and were removed. */
        CLEARED,
        /** Non-air blocks occupy cells and were left in place (standard-player policy). */
        BLOCKED
    }

    /**
     * Inspects (and optionally clears) the blocks occupying the prospective collision
     * cells of a placement — the exact cells {@link #spawnModel} would fill with
     * barriers. Used to guarantee a multi-block never spawns inside existing terrain.
     *
     * @param clear true to remove non-air occupants (creative/admin policy); false to
     *              only report whether the area is free (standard-player policy)
     */
    public SpawnAreaResult prepareSpawnArea(VirtualModel model,
                                            VirtualBlockPlacementHelper.PlacementData placement,
                                            boolean clear) {
        SpawnContext context = resolveSpawnContext(model, placement);
        boolean cleared = false;
        for (int i = 0; i + 2 < context.cells().length; i += 3) {
            Location location = context.origin().clone().add(
                    context.cells()[i], context.cells()[i + 1], context.cells()[i + 2]);
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                continue;
            }
            if (!clear) {
                return SpawnAreaResult.BLOCKED;
            }
            block.setType(Material.AIR);
            cleared = true;
        }
        return cleared ? SpawnAreaResult.CLEARED : SpawnAreaResult.CLEAR;
    }

    public void removeModel(UUID instanceId) {
        removeModelInternal(instanceId);
    }

    public void removeAllModels() {
        for (UUID id : new ArrayList<>(activeBlocks.keySet())) {
            removeModelInternal(id);
        }
    }

    public void shutdown() {
        removeAllModels();
        if (displayTransport != null) {
            displayTransport.shutdown();
            displayTransport = null;
        }
    }

    public boolean exists(UUID instanceId) {
        return activeBlocks.containsKey(instanceId);
    }

    public Set<Location> getBarrierLocations(UUID instanceId) {
        ActiveVirtualBlock activeBlock = activeBlocks.get(instanceId);
        if (activeBlock == null) {
            return Set.of();
        }
        return activeBlock.barrierBlocks();
    }

    public ActiveVirtualBlock getVirtualBlockAt(Location location) {
        if (location == null) {
            return null;
        }
        UUID instanceId = blockToModelMap.get(BlockCoordinate.from(location));
        if (instanceId != null) {
            return activeBlocks.get(instanceId);
        }
        return null;
    }

    public UUID getInstanceIdAt(Location location) {
        if (location == null) {
            return null;
        }
        return blockToModelMap.get(BlockCoordinate.from(location));
    }

    @EventHandler
    public void onBarrierBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != BARRIER_MATERIAL) {
            return;
        }

        UUID instanceId = blockToModelMap.get(BlockCoordinate.from(block.getLocation()));
        if (instanceId != null) {
            event.setDropItems(false);
            removeModelInternal(instanceId);
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
        voxelBakes.clear();
        modelSourceFiles.clear();
        if (textureImageStore != null) {
            textureImageStore.clear();
        }
        occupancyCalculator.clearCache();
        if (plugin == null) {
            return;
        }

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
            registerModel(entry.key(), model, ModelMeta.load(entry.file()), entry.file());
            textureReports.put(entry.key(), resolveTextureReport(model));
        }
    }

    /**
     * Bakes texel surface plans for a registered model using the current settings.
     * Bake failures never break model load — faces without resolvable PNGs simply
     * keep their legacy rendering tiers.
     */
    private void bakeTexelSurfaces(String key, VirtualModel model, ModelMeta meta, File modelFile) {
        TexelBakeResult result = TexelSurfaceBaker.bakeModel(
                model, meta, modelFile, imageStore(), texelSettings);
        texelBakes.put(key, result);
        if (result.enabled() && result.facesBaked() > 0) {
            DebugLogger.info("[TexelBaking] Model '" + key + "': baked " + result.facesBaked()
                    + "/" + result.facesTotal() + " face(s) into " + result.totalPlates()
                    + " merged plate(s) in " + (result.bakeTimeNanos() / 1_000_000.0) + " ms.");
        }
    }

    /**
     * Bakes the adaptive rendering strategy decision and, when the voxel strategy
     * is selected, the merged voxel runs for a registered model. Bake failures
     * never break model load — the legacy pipeline renders the model unchanged.
     */
    private void bakeVoxelPlan(String key, VirtualModel model, ModelMeta meta, File modelFile) {
        VoxelModelBake result = VoxelSurfaceBaker.bakeModel(
                model, meta, modelFile, imageStore(), voxelSettings, settings,
                texelBakes.get(key), effectiveOriginMode(model));
        voxelBakes.put(key, result);
    }

    private TextureImageStore imageStore() {
        if (textureImageStore == null) {
            File root = plugin != null ? new File(plugin.getDataFolder(), MODELS_FOLDER) : null;
            textureImageStore = new TextureImageStore(root);
        }
        return textureImageStore;
    }

    /**
     * Deterministic per-model texture-resolution report. Builds the same tiered
     * {@link TextureMaterialResolver} diagnostics already used elsewhere, but in a
     * {@link LinkedHashMap} so ordering is stable across reloads (mirrors the
     * FMM determinism fix where non-deterministic key order rewrote the resource
     * pack hash every restart).
     */
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
        String relative = filePath.startsWith(basePath)
                ? filePath.substring(basePath.length())
                : file.getName();
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
    ) {
    }

    /** Resolves the effective modes, anchor, rotation, and collision cells for a spawn. */
    private SpawnContext resolveSpawnContext(
            VirtualModel model,
            VirtualBlockPlacementHelper.PlacementData placement
    ) {
        ModelMeta meta = getModelMeta(model.name());
        ModelMeta.CollisionMode collisionMode = meta.collisionMode() != null
                ? meta.collisionMode() : settings.collisionMode();
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
        Quaternionf globalRotation = snapped != null
                ? snapped.quaternion()
                : new Quaternionf(placement.globalRotation());

        int[] cells = occupancyCalculator.compute(
                model, snapped, placement.globalRotation(), collisionMode,
                settings.collisionEpsilon(), originMode);

        return new SpawnContext(collisionMode, originMode, origin, snapped, globalRotation, cells);
    }

    private UUID spawnModel(
            VirtualModel model,
            VirtualBlockPlacementHelper.PlacementData placement,
            UUID instanceId
    ) {
        SpawnContext context = resolveSpawnContext(model, placement);
        ModelMeta.OriginMode originMode = context.originMode();
        Location origin = context.origin();
        Quaternionf globalRotation = context.globalRotation();
        int[] cells = context.cells();

        // CENTER: pixel (0,0,0) = anchor block center at its base; centered models
        // rotate about the block center. GRID: pixel (0,0,0) = block corner.
        Location displayOrigin = originMode == ModelMeta.OriginMode.GRID
                ? origin.clone()
                : origin.clone().add(0.5, 0.0, 0.5);

        List<UUID> spawnedEntities = new ArrayList<>();
        Set<Location> barrierBlocks = new HashSet<>();

        for (int i = 0; i + 2 < cells.length; i += 3) {
            Location location = origin.clone().add(cells[i], cells[i + 1], cells[i + 2]);
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                block.setType(BARRIER_MATERIAL);
                barrierBlocks.add(location);
                blockToModelMap.put(BlockCoordinate.from(location), instanceId);
            } else if (settings.collisionNonAirPolicy() == VirtualRenderingSettings.NonAirPolicy.STRICT) {
                rollbackSpawn(barrierBlocks, spawnedEntities);
                DebugLogger.warning("spawnModel: collision cell " + location + " is not air; "
                        + "STRICT policy aborted the spawn of model '" + model.name() + "'.");
                return null;
            }
        }

        // Pivot: rotations rotate the model about the anchor block's center (vanilla
        // block behavior): t' = R·t + (C−DO) − R·(C−DO), where C = (0.5, 0.5, 0.5)
        // and DO = display spawn offset — identical to the voxelization's placement.
        Vector3f pivotOffset = new Vector3f(
                0.5f - (float) (displayOrigin.getX() - origin.getX()),
                0.5f,
                0.5f - (float) (displayOrigin.getZ() - origin.getZ()));
        Vector3f rotatedPivotOffset = new Vector3f(pivotOffset);
        globalRotation.transform(rotatedPivotOffset);

        boolean animated = model.hasAnimations();
        List<AnimationBinding> animationBindings = animated ? new ArrayList<>() : null;
        TexelBakeResult texelBake = texelBakes.get(model.name().toLowerCase(Locale.ROOT));
        List<Map<CubeFace, TexelSurfacePlan>> texelCubePlans =
                texelBake != null && texelBake.enabled() ? texelBake.cubePlans() : null;
        VoxelModelBake voxelBake = voxelBakes.get(model.name().toLowerCase(Locale.ROOT));
        // The strategy selection already refuses animated models (voxel displays
        // cannot bind to cube bones); the guard keeps a stale bake honest. A bake
        // that selected VOXEL but produced zero runs (thin geometry whose cubes
        // strictly overlap no lattice cell) must not render an invisible model —
        // it falls back to the legacy cube pipeline below.
        boolean voxelRender = voxelBake != null && voxelBake.voxelRender()
                && !voxelBake.runs().isEmpty() && !animated;
        // Readability floor for palette-quantized models (texel plates or voxel
        // reconstruction): vanilla's directional face shading crushes near-black
        // palette materials into one unreadable mass outside full daylight. A
        // per-model meta override (texelBrightness, 0-15) keeps every display at
        // a minimum light level so the palette art stays legible while the
        // top/side/bottom shading still separates the faces.
        ModelMeta spawnMeta = getModelMeta(model.name());
        int brightnessFloor = spawnMeta.texelBrightness() != null
                && (voxelRender || texelCubePlans != null)
                ? spawnMeta.texelBrightness() : 0;

        if (voxelRender) {
            // Voxel reconstruction: one display per merged XZ rectangle of
            // same-color, same-emission voxels. Run origins are model-space
            // (lattice-shifted), so they compose through the identical
            // rotation/pivot math as cube displays and can never drift from the
            // barrier lattice.
            for (VoxelModelBake.VoxelRun run : voxelBake.runs()) {
                DisplayEmitter.EmittedDisplay item = new DisplayEmitter.EmittedDisplay(
                        TexelPalette.material(run.paletteIndex()),
                        new Vector3f(run.x(), run.y(), run.z()),
                        new Quaternionf(),
                        new Vector3f(run.lengthX(), 1.0f, run.widthZ()),
                        new Quaternionf(),
                        run.lightEmission(),
                        () -> TexelPalette.blockData(run.paletteIndex())
                );
                spawnedEntities.add(spawnDisplayEntity(
                        displayOrigin, instanceId, item, brightnessFloor,
                        globalRotation, pivotOffset, rotatedPivotOffset));
            }
        } else {
            int cubeIndex = 0;
            for (BakedCube cube : model.cubes()) {
                Map<CubeFace, TexelSurfacePlan> facePlans =
                        texelCubePlans != null && cubeIndex < texelCubePlans.size()
                                ? texelCubePlans.get(cubeIndex)
                                : null;
                for (DisplayEmitter.EmittedDisplay item
                        : DisplayEmitter.emitCube(cube, settings.perFaceRendering(), facePlans)) {
                    UUID displayId = spawnDisplayEntity(
                            displayOrigin, instanceId, item, brightnessFloor,
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
        }

        Vector3f pivotCorrection = null;
        if (animated && !animationBindings.isEmpty()) {
            pivotCorrection = new Vector3f(pivotOffset).sub(rotatedPivotOffset);
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
        if (displayTransport != null && displayTransport.isRunning()) {
            // Registers the chunk index, attaches in-range viewers and drains the
            // initial dirty state so the first animation delta carries only changes.
            displayTransport.finishInstance(instanceId, displayOrigin, animated);
        }
        return instanceId;
    }

    /** Shared brightness instances per emission level (0-15); Bukkit allocates per call otherwise. */
    private static final Display.Brightness[] BRIGHTNESS_BY_LEVEL = new Display.Brightness[16];

    static {
        for (int level = 0; level < BRIGHTNESS_BY_LEVEL.length; level++) {
            BRIGHTNESS_BY_LEVEL[level] = new Display.Brightness(level, 15);
        }
    }

    /**
     * Spawns one {@code BlockDisplay} for an emitted display item at the shared
     * display origin: block data, instance tag, brightness floor, and the
     * rotation/pivot composition {@code t' = R·t + (C−DO) − R·(C−DO)} every
     * rendering strategy (classic plates, texel plates, voxel runs) funnels
     * through, so all strategies stay on one transform pipeline.
     */
    private UUID spawnDisplayEntity(
            Location displayOrigin,
            UUID instanceId,
            DisplayEmitter.EmittedDisplay item,
            int brightnessFloor,
            Quaternionf globalRotation,
            Vector3f pivotOffset,
            Vector3f rotatedPivotOffset
    ) {
        if (displayTransport != null && displayTransport.isRunning()) {
            return spawnPooledDisplayEntity(
                    displayOrigin, instanceId, item, brightnessFloor,
                    globalRotation, pivotOffset, rotatedPivotOffset);
        }        BlockDisplay display = (BlockDisplay) displayOrigin.getWorld().spawnEntity(
                displayOrigin, EntityType.BLOCK_DISPLAY);
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

    /**
     * Transport path: the display is a pooled, never-spawned entity streamed to
     * viewers per player with LOD tiers and bundle batching. The instance tag
     * stays on the Bukkit entity so sweep logic can still identify strays.
     */
    private UUID spawnPooledDisplayEntity(
            Location displayOrigin,
            UUID instanceId,
            DisplayEmitter.EmittedDisplay item,
            int brightnessFloor,
            Quaternionf globalRotation,
            Vector3f pivotOffset,
            Vector3f rotatedPivotOffset
    ) {
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

        pooled.moveTo(displayTransport.nms(),
                displayOrigin.getX(), displayOrigin.getY(), displayOrigin.getZ(), 0f, 0f);
        pooled.setTransform(new Matrix4f()
                .translate(translation)
                .rotate(new Quaternionf(globalRotation).mul(item.leftRotation()))
                .scale(item.scale())
                .rotate(item.rightRotation()), 0);
        return display.getUniqueId();
    }

    /**
     * The model's effective origin mode — the single resolution used by both the
     * spawn path and the voxel bake, so the voxel lattice can never disagree
     * with the anchor the displays spawn from.
     */
    private ModelMeta.OriginMode effectiveOriginMode(VirtualModel model) {
        ModelMeta meta = getModelMeta(model.name());
        ModelMeta.OriginMode originMode = meta.originMode() != null
                ? meta.originMode() : settings.originMode();
        if (originMode == null || originMode == ModelMeta.OriginMode.AUTO) {
            originMode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());
        }
        return originMode;
    }

    private void rollbackSpawn(Set<Location> barrierBlocks, List<UUID> spawnedEntities) {
        for (Location loc : barrierBlocks) {
            if (loc.getBlock().getType() == BARRIER_MATERIAL) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(BlockCoordinate.from(loc));
        }
        for (UUID displayId : spawnedEntities) {
            Entity display = Bukkit.getEntity(displayId);
            if (display != null) {
                display.remove();
            }
        }
    }

    public void cleanupGhostEntities(UUID instanceId) {
        String tag = DISPLAY_TAG_PREFIX + instanceId;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                }
            }
        }
    }

    /**
     * Sweeps every loaded chunk in every world for tagged display entities whose
     * rendered-model id is no longer live, and removes them — the startup complement
     * to {@link #onChunkLoad}. Chunks near players load during world startup, before
     * this plugin's chunk listener registers, so entities persisted by a previous
     * session in those chunks never see a {@code ChunkLoadEvent}; without this sweep
     * they survive alongside the fresh displays that {@code restoreForState} spawns
     * under new ids and z-fight them exactly in place (color flicker with camera
     * movement). Semantics mirror the chunk-load handler: entities belonging to a
     * live multiblock instance are kept (its model may legitimately not be restored
     * yet — deferred world), everything else tagged with our prefix but absent from
     * {@code activeBlocks} is a ghost.
     *
     * @return the number of ghost displays removed
     */
    public int sweepGhostDisplays() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
                String instanceTag = null;
                for (String tag : entity.getScoreboardTags()) {
                    if (tag.startsWith(DISPLAY_TAG_PREFIX)) {
                        instanceTag = tag;
                        break;
                    }
                }
                if (instanceTag == null) {
                    continue;
                }
                try {
                    UUID instanceId = UUID.fromString(instanceTag.substring(DISPLAY_TAG_PREFIX.length()));
                    if (activeBlocks.containsKey(instanceId)) {
                        continue;
                    }
                    if (lifecycleManager != null
                            && lifecycleManager.registry().getInstance(instanceId) != null) {
                        continue;
                    }
                    entity.remove();
                    removed++;
                } catch (IllegalArgumentException ignored) {
                    // Malformed tag — treat as a ghost too.
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            DebugLogger.info("sweepGhostDisplays: removed " + removed
                    + " stale display entit" + (removed == 1 ? "y" : "ies")
                    + " from loaded chunks.");
        }
        return removed;
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (displayTransport != null && displayTransport.isRunning()) {
            displayTransport.handleChunkLoad(event.getChunk());
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof BlockDisplay)) {
                continue;
            }

            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith(DISPLAY_TAG_PREFIX)) {
                    try {
                        UUID instanceId = UUID.fromString(tag.substring(DISPLAY_TAG_PREFIX.length()));
                        if (!activeBlocks.containsKey(instanceId)) {
                            if (lifecycleManager != null && lifecycleManager.registry().getInstance(instanceId) != null) {
                                continue;
                            }
                            entity.remove();
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Malformed tag
                    }
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

    private void removeModelInternal(UUID instanceId) {
        ActiveVirtualBlock activeBlock = activeBlocks.remove(instanceId);
        if (activeBlock == null) {
            return;
        }

        for (Location loc : activeBlock.barrierBlocks()) {
            if (loc.getBlock().getType() == BARRIER_MATERIAL) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(BlockCoordinate.from(loc));
        }

        if (displayTransport != null && displayTransport.isRunning()) {
            // Despawns every pooled display for every viewer and returns them to the pool.
            displayTransport.removeInstance(instanceId);
            return;
        }
        for (UUID displayId : activeBlock.displayEntities()) {
            Entity display = Bukkit.getEntity(displayId);
            if (display != null) {
                display.remove();
            }
        }
    }

    public UUID restoreForState(BlockCoordinate anchor, String modelKey, Quaternionf rotation) {
        if (blockToModelMap.containsKey(anchor)) {
            return blockToModelMap.get(anchor);
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
        VirtualBlockPlacementHelper.PlacementData placement =
                new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        UUID instanceId = spawnModel(model, placement, UUID.randomUUID());
        if (instanceId != null) {
            DebugLogger.info("restoreForState: Spawned virtual block for model key '" + modelKey + "' at " + anchor + " (instanceId=" + instanceId + ").");
        }
        return instanceId;
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
