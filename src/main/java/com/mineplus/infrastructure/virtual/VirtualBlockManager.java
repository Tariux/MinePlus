// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.infrastructure.virtual.animation.AnimationBinding;
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

    private JavaPlugin plugin;
    private VirtualRenderingSettings settings = VirtualRenderingSettings.defaults();
    private MultiBlockLifecycleManager lifecycleManager;

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

    public void setLifecycleManager(MultiBlockLifecycleManager manager) {
        this.lifecycleManager = manager;
    }

    public void updateSettings(VirtualRenderingSettings settings) {
        this.settings = settings == null ? VirtualRenderingSettings.defaults() : settings;
        occupancyCalculator.clearCache();
    }

    public VirtualRenderingSettings settings() {
        return settings;
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

    public void registerModel(String name, VirtualModel model) {
        registerModel(name, model, ModelMeta.empty());
    }

    public void registerModel(String name, VirtualModel model, ModelMeta meta) {
        if (name == null || model == null) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        loadedModels.put(key, model);
        modelMeta.put(key, meta == null ? ModelMeta.empty() : meta);
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
            registerModel(entry.key(), model, ModelMeta.load(entry.file()));
            textureReports.put(entry.key(), resolveTextureReport(model));
        }
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
        ModelMeta.OriginMode originMode = meta.originMode() != null
                ? meta.originMode() : settings.originMode();
        if (originMode == null || originMode == ModelMeta.OriginMode.AUTO) {
            originMode = ModelMeta.OriginMode.forModel(model.modelFormat(), model.cubes());
        }

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
        for (BakedCube cube : model.cubes()) {
            for (DisplayEmitter.EmittedDisplay item
                    : DisplayEmitter.emitCube(cube, settings.perFaceRendering())) {
                BlockDisplay display = (BlockDisplay) origin.getWorld().spawnEntity(
                        displayOrigin, EntityType.BLOCK_DISPLAY);
                display.setBlock(item.blockData());
                display.addScoreboardTag(DISPLAY_TAG_PREFIX + instanceId);
                if (item.lightEmission() > 0) {
                    display.setBrightness(new Display.Brightness(item.lightEmission(), 15));
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
                spawnedEntities.add(display.getUniqueId());

                if (animated && cube.boneIndex() >= 0) {
                    animationBindings.add(new AnimationBinding(
                            cube.boneIndex(),
                            display.getUniqueId(),
                            new Matrix4f()
                                    .translate(item.translation())
                                    .rotate(item.leftRotation())
                                    .scale(item.scale())
                                    .rotate(item.rightRotation())
                    ));
                }
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
        return instanceId;
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

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
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
