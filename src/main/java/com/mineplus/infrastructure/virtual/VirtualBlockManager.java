// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.model.BlockCoordinate;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VirtualBlockManager implements Listener {

    private static final String DISPLAY_TAG_PREFIX = "mineplus_vblock:";
    private static final Material FALLBACK_MATERIAL = Material.WHITE_CONCRETE;
    private static final String MODELS_FOLDER = "models";
    private static final String DEBUG_MODELS_FOLDER = "debug";

    private final Map<String, VirtualModel> loadedModels = new HashMap<>();
    private final Map<BlockCoordinate, UUID> blockToModelMap = new HashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new HashMap<>();
    private JavaPlugin plugin;
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

    public void registerModel(String name, VirtualModel model) {
        if (name == null || model == null) {
            return;
        }
        loadedModels.put(name.toLowerCase(Locale.ROOT), model);
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
        if (plugin == null) {
            return;
        }

        loadExternalModelDefinitions(plugin.getDataFolder());
        plugin.getLogger().info("Virtual models ready: " + loadedModels.size());
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
                loadedModels.put(name.toLowerCase(Locale.ROOT), model);
                loaded++;
            }
        }

        if (loaded > 0) {
            plugin.getLogger().info("Loaded " + loaded + " external model(s) from " + modelsFolder.getPath());
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
        List<UUID> spawnedEntities = new ArrayList<>();
        Set<Location> barrierBlocks = new HashSet<>();

        Location origin = new Location(
                placement.location().getWorld(),
                placement.location().getBlockX(),
                placement.location().getBlockY(),
                placement.location().getBlockZ()
        );
        Location displayOrigin = origin.clone().add(0.5, 0.0, 0.5);
        Quaternionf globalRotation = new Quaternionf(placement.globalRotation());

        VirtualBoundingBox box = VirtualBoundingBox.calculate(model);
        for (Vector offset : box.getOccupiedOffsets()) {
            Vector3f rotatedOffset = new Vector3f((float) offset.getX(), (float) offset.getY(), (float) offset.getZ());
            globalRotation.transform(rotatedOffset);

            Location location = origin.clone().add(
                    Math.round(rotatedOffset.x),
                    Math.round(rotatedOffset.y),
                    Math.round(rotatedOffset.z)
            );
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                block.setType(Material.BARRIER);
                barrierBlocks.add(location);
                blockToModelMap.put(BlockCoordinate.from(location), instanceId);
            }
        }

        for (BakedCube cube : model.cubes()) {
            BlockDisplay display = (BlockDisplay) origin.getWorld().spawnEntity(displayOrigin, EntityType.BLOCK_DISPLAY);
            Material cubeMaterial = resolveCubeMaterial(cube);
            display.setBlock(cubeMaterial.createBlockData());
            display.addScoreboardTag(DISPLAY_TAG_PREFIX + instanceId);

            Vector3f translated = new Vector3f(cube.translation());
            globalRotation.transform(translated);
            Quaternionf combinedRotation = new Quaternionf(globalRotation).mul(cube.leftRotation());

            Transformation transformation = new Transformation(
                    translated,
                    combinedRotation,
                    cube.scale(),
                    cube.rightRotation()
            );
            display.setTransformation(transformation);
            spawnedEntities.add(display.getUniqueId());

            if (debugLoggingEnabled) {
                plugin.getLogger().info("Rendered cube '" + cube.name()
                        + "' tex=" + (cube.primaryTexture() == null ? "none" : cube.primaryTexture())
                        + " mat=" + cubeMaterial.name()
                        + " translation=" + vectorString(translated)
                        + " scale=" + vectorString(cube.scale())
                        + " localRotation=" + quaternionString(cube.leftRotation())
                        + " globalRotation=" + quaternionString(globalRotation));
                for (Map.Entry<CubeFace, BakedFace> face : cube.faces().entrySet()) {
                    BakedFace data = face.getValue();
                    plugin.getLogger().info(" - face " + face.getKey().name().toLowerCase(Locale.ROOT)
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

    private Material resolveCubeMaterial(BakedCube cube) {
        String textureId = cube.primaryTexture();
        if (textureId == null || textureId.isBlank()) {
            return FALLBACK_MATERIAL;
        }

        String normalized = textureId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        Material matched = Material.matchMaterial(normalized);
        if (matched == null) {
            matched = Material.matchMaterial(textureId);
        }
        if (matched == null || !matched.isBlock() || matched.isAir()) {
            return FALLBACK_MATERIAL;
        }
        return matched;
    }

    public UUID restoreForState(BlockCoordinate anchor, String modelKey, Quaternionf rotation) {
        if (blockToModelMap.containsKey(anchor)) {
            return blockToModelMap.get(anchor);
        }
        VirtualModel model = getModel(modelKey);
        if (model == null) {
            if (plugin != null) {
                plugin.getLogger().warning("Cannot restore virtual block: unknown model key '" + modelKey + "' at " + anchor + ".");
            }
            return null;
        }
        World world = Bukkit.getWorld(anchor.worldName());
        if (world == null) {
            if (plugin != null) {
                plugin.getLogger().info("restoreForState: World '" + anchor.worldName() + "' not loaded for model key '" + modelKey + "'.");
            }
            return null;
        }
        Location origin = new Location(world, anchor.x(), anchor.y(), anchor.z());
        VirtualBlockPlacementHelper.PlacementData placement =
                new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        UUID instanceId = spawnModel(model, placement, UUID.randomUUID());
        if (plugin != null) {
            plugin.getLogger().info("restoreForState: Spawned virtual block for model key '" + modelKey + "' at " + anchor + " (instanceId=" + instanceId + ").");
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
