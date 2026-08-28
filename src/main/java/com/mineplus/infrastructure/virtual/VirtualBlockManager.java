// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VirtualBlockManager implements Listener {

    private static final String DISPLAY_TAG_PREFIX = "mineplus_vblock:";
    private static final String MODELS_FOLDER = "models";
    private static final String DEBUG_MODELS_FOLDER = "debug";

    private final Map<String, VirtualModel> loadedModels = new HashMap<>();
    private final Map<String, ModelMeta> modelMeta = new HashMap<>();
    private final Map<String, List<String>> unresolvedTexturesByModel = new HashMap<>();
    private final Map<BlockCoordinate, UUID> blockToModelMap = new HashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new HashMap<>();
    private final VoxelOccupancyCalculator occupancyCalculator = new VoxelOccupancyCalculator();

    private JavaPlugin plugin;
    private VirtualRenderingSettings settings = VirtualRenderingSettings.defaults();
    private boolean debugLoggingEnabled;
    private MultiBlockLifecycleManager lifecycleManager;

    public record ActiveVirtualBlock(
            String modelName,
            Location origin,
            Quaternionf rotation,
            List<UUID> displayEntities,
            Set<Location> barrierBlocks
    ) {
    }

    public void loadModels(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debugLoggingEnabled = Boolean.getBoolean("mineplus.debug.models");
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

    /** Texture names of a model that fell through to the fallback material (empty when all resolved). */
    public List<String> getUnresolvedTextures(String modelName) {
        if (modelName == null) {
            return List.of();
        }
        List<String> unresolved = unresolvedTexturesByModel.get(modelName.toLowerCase(Locale.ROOT));
        return unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public VoxelOccupancyCalculator occupancyCalculator() {
        return occupancyCalculator;
    }

    public UUID spawnModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        return spawnModel(model, placement, UUID.randomUUID());
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
        if (block.getType() != Material.BARRIER) {
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
        unresolvedTexturesByModel.clear();
        occupancyCalculator.clearCache();
        if (plugin == null) {
            return;
        }

        loadExternalModelDefinitions(plugin.getDataFolder());
        DebugLogger.info("Virtual models ready: " + loadedModels.size());
    }

    private void loadExternalModelDefinitions(File pluginFolder) {
        File modelsFolder = new File(pluginFolder, MODELS_FOLDER);
        if (!modelsFolder.exists() && !modelsFolder.mkdirs()) {
            return;
        }
        File debugFolder = new File(modelsFolder, DEBUG_MODELS_FOLDER);
        if (!debugFolder.exists()) {
            debugFolder.mkdirs();
        }

        List<File> files = new ArrayList<>();
        collectModelFiles(modelsFolder, files);
        files.sort(java.util.Comparator.comparing(File::getPath));

        int loaded = 0;
        for (File file : files) {
            String name = modelKeyFromFile(modelsFolder, file);
            VirtualModel model = BbModelImporter.parse(name, file, plugin.getLogger());
            if (model != null && !model.cubes().isEmpty()) {
                registerModel(name, model, ModelMeta.load(file));
                loaded++;
            }
        }

        if (loaded > 0) {
            DebugLogger.info("Loaded " + loaded + " external model(s) from " + modelsFolder.getPath());
        }
    }

    private void collectModelFiles(File folder, List<File> output) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectModelFiles(child, output);
                continue;
            }
            if (child.getName().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
                output.add(child);
            }
        }
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

    private UUID spawnModel(
            VirtualModel model,
            VirtualBlockPlacementHelper.PlacementData placement,
            UUID instanceId
    ) {
        ModelMeta meta = getModelMeta(model.name());
        ModelMeta.CollisionMode collisionMode = meta.collisionMode() != null
                ? meta.collisionMode() : settings.collisionMode();
        ModelMeta.OriginMode originMode = meta.originMode() != null
                ? meta.originMode() : settings.originMode();
        VirtualModel.TextureMode textureMode = meta.textureMode() != null
                ? meta.textureMode() : settings.textureMode();

        Location origin = new Location(
                placement.location().getWorld(),
                placement.location().getBlockX(),
                placement.location().getBlockY(),
                placement.location().getBlockZ()
        );
        // CENTER (vanilla convention): pixel (0,0,0) = anchor block center at its base;
        // displays spawn at the block center so centered models rotate about it and
        // single-block models stay within one block. GRID: spawn at the block corner.
        Location displayOrigin = originMode == ModelMeta.OriginMode.GRID
                ? origin.clone()
                : origin.clone().add(0.5, 0.0, 0.5);

        RotationSnapper.SnappedRotation snapped = settings.rotationSnap()
                ? RotationSnapper.snap(placement.globalRotation(), settings.rotationSnapThresholdDegrees())
                : null;
        Quaternionf globalRotation = snapped != null
                ? snapped.quaternion()
                : new Quaternionf(placement.globalRotation());

        List<UUID> spawnedEntities = new ArrayList<>();
        Set<Location> barrierBlocks = new HashSet<>();

        // Collision lattice: geometry-aware voxelization under the exact same
        // T(anchorOffset)·R·M transform the displays use; cells are already final.
        int[] cells = occupancyCalculator.compute(
                model, snapped, placement.globalRotation(), collisionMode,
                settings.collisionEpsilon(), originMode);
        for (int i = 0; i + 2 < cells.length; i += 3) {
            Location location = origin.clone().add(cells[i], cells[i + 1], cells[i + 2]);
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                block.setType(Material.BARRIER);
                barrierBlocks.add(location);
                blockToModelMap.put(BlockCoordinate.from(location), instanceId);
            } else if (settings.collisionNonAirPolicy() == VirtualRenderingSettings.NonAirPolicy.STRICT) {
                rollbackSpawn(barrierBlocks, spawnedEntities, instanceId);
                DebugLogger.warning("spawnModel: collision cell " + location + " is not air; "
                        + "STRICT policy aborted the spawn of model '" + model.name() + "'.");
                return null;
            }
        }

        recordUnresolvedTextures(model);

        // Visual emission: fast path single display per uniform cube, plates for mixed faces.
        for (BakedCube cube : model.cubes()) {
            List<DisplayEmitter.EmittedDisplay> emitted =
                    DisplayEmitter.emitCube(model, cube, textureMode, settings.perFaceRendering());
            for (DisplayEmitter.EmittedDisplay item : emitted) {
                BlockDisplay display = (BlockDisplay) origin.getWorld().spawnEntity(
                        displayOrigin, EntityType.BLOCK_DISPLAY);
                display.setBlock(DisplayEmitter.blockDataFor(item.material()));
                display.addScoreboardTag(DISPLAY_TAG_PREFIX + instanceId);
                if (item.lightEmission() > 0) {
                    display.setBrightness(new Display.Brightness(item.lightEmission(), 15));
                }

                Vector3f translation = new Vector3f(item.translation());
                globalRotation.transform(translation);
                Quaternionf combinedRotation = new Quaternionf(globalRotation).mul(item.leftRotation());

                display.setTransformation(new org.bukkit.util.Transformation(
                        translation,
                        combinedRotation,
                        item.scale(),
                        item.rightRotation()
                ));
                spawnedEntities.add(display.getUniqueId());

                if (DebugLogger.isEnabled()) {
                    DebugLogger.info("Rendered '" + item.source()
                            + "' mat=" + item.material().name()
                            + " light=" + item.lightEmission()
                            + " translation=" + vectorString(translation)
                            + " scale=" + vectorString(item.scale())
                            + " localRotation=" + quaternionString(item.leftRotation())
                            + " globalRotation=" + quaternionString(globalRotation));
                }
            }

            if (DebugLogger.isEnabled()) {
                for (Map.Entry<CubeFace, BakedFace> face : cube.faces().entrySet()) {
                    BakedFace data = face.getValue();
                    DebugLogger.info(" - face " + face.getKey().name().toLowerCase(Locale.ROOT)
                            + " uv=[" + data.u1() + "," + data.v1() + "," + data.u2() + "," + data.v2() + "]"
                            + " rotation=" + data.rotation()
                            + " textureRef=" + data.textureReference()
                            + " texture=" + data.textureName());
                }
            }
        }

        activeBlocks.put(instanceId, new ActiveVirtualBlock(
                model.name(),
                origin,
                globalRotation,
                spawnedEntities,
                barrierBlocks
        ));
        return instanceId;
    }

    private void rollbackSpawn(Set<Location> barrierBlocks, List<UUID> spawnedEntities, UUID instanceId) {
        for (Location loc : barrierBlocks) {
            if (loc.getBlock().getType() == Material.BARRIER) {
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

    private void recordUnresolvedTextures(VirtualModel model) {
        String key = model.name().toLowerCase(Locale.ROOT);
        if (unresolvedTexturesByModel.containsKey(key)) {
            return;
        }
        Set<String> textureNames = new java.util.LinkedHashSet<>();
        for (BakedCube cube : model.cubes()) {
            if (cube.primaryTexture() != null) {
                textureNames.add(cube.primaryTexture());
            }
            for (BakedFace face : cube.faces().values()) {
                if (face.textureName() != null) {
                    textureNames.add(face.textureName());
                }
            }
        }
        List<String> sortedNames = new ArrayList<>(textureNames);
        Collections.sort(sortedNames);
        List<String> unresolved = new ArrayList<>();
        for (String textureName : sortedNames) {
            TextureMaterialResolver.Resolution resolution = TextureMaterialResolver.resolveDetailed(textureName);
            if (resolution.isFallback()) {
                unresolved.add(textureName);
            }
        }
        unresolvedTexturesByModel.put(key, Collections.unmodifiableList(unresolved));
        if (!unresolved.isEmpty()) {
            DebugLogger.warning("Model '" + model.name() + "' has "
                    + unresolved.size() + " unresolved texture(s): " + String.join(", ", unresolved));
        }
    }

    public void cleanupGhostEntities(UUID instanceId) {
        String tag = DISPLAY_TAG_PREFIX + instanceId;
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntitiesByClass(org.bukkit.entity.BlockDisplay.class)) {
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof org.bukkit.entity.BlockDisplay)) {
                continue;
            }

            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith(DISPLAY_TAG_PREFIX)) {
                    String instanceIdStr = tag.substring(DISPLAY_TAG_PREFIX.length());
                    try {
                        UUID instanceId = UUID.fromString(instanceIdStr);
                        if (!activeBlocks.containsKey(instanceId)) {
                            if (lifecycleManager != null && lifecycleManager.registry().getInstance(instanceId) != null) {
                                continue;
                            }
                            entity.remove();
                            DebugLogger.info("Removed ghost entity " + entity.getUniqueId() + " in loaded chunk.");
                        }
                    } catch (IllegalArgumentException e) {
                        // Ignore
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
            if (loc.getBlock().getType() == Material.BARRIER) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(BlockCoordinate.from(loc));
        }

        for (UUID displayId : activeBlock.displayEntities()) {
            Entity display = org.bukkit.Bukkit.getEntity(displayId);
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
            if (plugin != null) {
                DebugLogger.warning("Cannot restore virtual block: unknown model key '" + modelKey + "' at " + anchor + ".");
            }
            return null;
        }
        World world = Bukkit.getWorld(anchor.worldName());
        if (world == null) {
            if (plugin != null) {
                DebugLogger.info("restoreForState: World '" + anchor.worldName() + "' not loaded for model key '" + modelKey + "'.");
            }
            return null;
        }
        Location origin = new Location(world, anchor.x(), anchor.y(), anchor.z());
        VirtualBlockPlacementHelper.PlacementData placement =
                new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        UUID instanceId = spawnModel(model, placement, UUID.randomUUID());
        if (plugin != null) {
            DebugLogger.info("restoreForState: Spawned virtual block for model key '" + modelKey + "' at " + anchor + " (instanceId=" + instanceId + ").");
        }
        return instanceId;
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String vectorString(Vector3f value) {
        return "(" + value.x + ", " + value.y + ", " + value.z + ")";
    }

    private String quaternionString(Quaternionf value) {
        return "(" + value.x + ", " + value.y + ", " + value.z + ", " + value.w + ")";
    }
}
