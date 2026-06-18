// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mineplus.infrastructure.persistence.PersistenceFacade;
import com.mineplus.infrastructure.persistence.snapshot.VirtualBlockSnapshot;
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
    private static final Vector3f DISPLAY_OFFSET = new Vector3f(0.5f, 0.0f, 0.5f);
    private static final Material FALLBACK_MATERIAL = Material.WHITE_CONCRETE;
    private static final String MODELS_FOLDER = "models";
    private static final String DEBUG_MODELS_FOLDER = "debug";

    private final Map<String, VirtualModel> loadedModels = new HashMap<>();
    private final Map<Location, UUID> blockToModelMap = new HashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new HashMap<>();
    private PersistenceFacade persistence;
    private JavaPlugin plugin;
    private boolean debugLoggingEnabled;

    public record ActiveVirtualBlock(
            String modelName,
            Location origin,
            Quaternionf rotation,
            Map<String, UUID> cubeEntities,
            List<UUID> displayEntities,
            Set<Location> barrierBlocks
    ) {
    }

    public void loadModels(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debugLoggingEnabled = Boolean.getBoolean("mineplus.debug.models");
        loadModelDefinitions();
    }

    public void loadWithPersistence(PersistenceFacade persistence) {
        this.persistence = persistence;
        restoreSpawnedModels();
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
        return spawnModel(model, placement, true, UUID.randomUUID());
    }

    public void removeModel(UUID instanceId) {
        removeModel(instanceId, true);
    }

    public void removeAllModels() {
        for (UUID id : new ArrayList<>(activeBlocks.keySet())) {
            removeModel(id, false);
        }
    }

    public void transform(UUID instanceId, Location newOrigin, Quaternionf newRotation, int interpolationTicks) {
        ActiveVirtualBlock active = activeBlocks.get(instanceId);
        if (active == null || newOrigin.getWorld() == null) {
            return;
        }

        VirtualModel model = getModel(active.modelName());
        if (model == null) {
            return;
        }

        // Update barriers if moved to a different block
        if (newOrigin.getBlockX() != active.origin().getBlockX() ||
            newOrigin.getBlockY() != active.origin().getBlockY() ||
            newOrigin.getBlockZ() != active.origin().getBlockZ()) {

            for (Location loc : active.barrierBlocks()) {
                if (loc.getBlock().getType() == Material.BARRIER) {
                    loc.getBlock().setType(Material.AIR);
                }
                blockToModelMap.remove(loc);
            }
            active.barrierBlocks().clear();

            Set<Location> newBarriers = computeBarrierLocations(model, newOrigin, newRotation);
            for (Location loc : newBarriers) {
                if (loc.getBlock().getType().isAir()) {
                    loc.getBlock().setType(Material.BARRIER);
                    active.barrierBlocks().add(loc);
                    blockToModelMap.put(loc, instanceId);
                }
            }
        }

        Location displayOrigin = newOrigin.clone().add(0.5, 0.0, 0.5);
        for (BakedCube cube : model.cubes()) {
            UUID entityId = active.cubeEntities().get(cube.name());
            if (entityId == null) continue;

            Entity entity = Bukkit.getEntity(entityId);
            if (!(entity instanceof BlockDisplay display)) continue;

            Vector3f translated = new Vector3f(cube.translation());
            newRotation.transform(translated);
            Quaternionf combinedRotation = new Quaternionf(newRotation).mul(cube.leftRotation());

            Transformation transformation = new Transformation(
                    translated,
                    combinedRotation,
                    cube.scale(),
                    cube.rightRotation()
            );

            display.setInterpolationDuration(interpolationTicks);
            display.setInterpolationDelay(0);
            display.setTransformation(transformation);
            display.teleport(displayOrigin);
        }

        activeBlocks.put(instanceId, new ActiveVirtualBlock(
                active.modelName(),
                newOrigin,
                newRotation,
                active.cubeEntities(),
                active.displayEntities(),
                active.barrierBlocks()
        ));
        saveAsync();
    }

    public void transformCube(UUID instanceId, String cubeName, Vector3f localTranslation, Quaternionf localRotation, int interpolationTicks) {
        ActiveVirtualBlock active = activeBlocks.get(instanceId);
        if (active == null) return;

        UUID entityId = active.cubeEntities().get(cubeName);
        if (entityId == null) return;

        Entity entity = Bukkit.getEntity(entityId);
        if (!(entity instanceof BlockDisplay display)) return;

        VirtualModel model = getModel(active.modelName());
        if (model == null) return;

        BakedCube cube = null;
        for (BakedCube c : model.cubes()) {
            if (cubeName.equals(c.name())) {
                cube = c;
                break;
            }
        }
        if (cube == null) return;

        Vector3f finalTranslation = new Vector3f(cube.translation()).add(localTranslation);
        active.rotation().transform(finalTranslation);

        Quaternionf finalRotation = new Quaternionf(active.rotation()).mul(cube.leftRotation()).mul(localRotation);

        Transformation transformation = new Transformation(
                finalTranslation,
                finalRotation,
                cube.scale(),
                cube.rightRotation()
        );

        display.setInterpolationDuration(interpolationTicks);
        display.setInterpolationDelay(0);
        display.setTransformation(transformation);
    }

    public void shutdown() {
        saveNow();
    }

    private void saveAsync() {
        if (persistence != null) {
            persistence.enqueueVirtualBlockReplace(activeBlocks.entrySet().stream()
                    .map(e -> new VirtualBlockSnapshot(
                            e.getKey(),
                            e.getValue().modelName(),
                            e.getValue().origin().getWorld().getName(),
                            e.getValue().origin().getBlockX(),
                            e.getValue().origin().getBlockY(),
                            e.getValue().origin().getBlockZ(),
                            e.getValue().rotation().x,
                            e.getValue().rotation().y,
                            e.getValue().rotation().z,
                            e.getValue().rotation().w,
                            e.getValue().cubeEntities(),
                            e.getValue().displayEntities()
                    )).toList());
        }
    }

    public ActiveVirtualBlock getVirtualBlockAt(Location location) {
        UUID instanceId = blockToModelMap.get(location);
        if (instanceId != null) {
            return activeBlocks.get(instanceId);
        }
        return null;
    }

    public UUID getInstanceIdAt(Location location) {
        if (location == null) {
            return null;
        }
        return blockToModelMap.get(location);
    }

    @EventHandler
    public void onBarrierBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARRIER) {
            return;
        }

        UUID instanceId = blockToModelMap.get(block.getLocation());
        if (instanceId != null) {
            event.setDropItems(false);
            removeModel(instanceId);
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
            boolean persist,
            UUID instanceId
    ) {
        List<UUID> spawnedEntities = new ArrayList<>();
        Map<String, UUID> cubeEntities = new HashMap<>();
        Set<Location> barrierBlocks = new HashSet<>();

        Location origin = new Location(
                placement.location().getWorld(),
                placement.location().getBlockX(),
                placement.location().getBlockY(),
                placement.location().getBlockZ()
        );
        Location displayOrigin = origin.clone().add(0.5, 0.0, 0.5);
        Quaternionf globalRotation = new Quaternionf(placement.globalRotation());

        Set<Vector> occupiedOffsets = VirtualBoundingBox.calculateVoxelOffsets(model, globalRotation, DISPLAY_OFFSET);
        for (Vector offset : occupiedOffsets) {
            Location location = origin.clone().add(offset);
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                block.setType(Material.BARRIER);
                barrierBlocks.add(location);
                blockToModelMap.put(location, instanceId);
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
            UUID entityId = display.getUniqueId();
            spawnedEntities.add(entityId);
            if (cube.name() != null && !cube.name().isBlank()) {
                cubeEntities.put(cube.name(), entityId);
            }

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
                cubeEntities,
                spawnedEntities,
                barrierBlocks
        ));
        if (persist) {
            saveAsync();
        }
        return instanceId;
    }

    private void removeModel(UUID instanceId, boolean persist) {
        ActiveVirtualBlock activeBlock = activeBlocks.remove(instanceId);
        if (activeBlock == null) {
            return;
        }

        for (Location loc : activeBlock.barrierBlocks()) {
            if (loc.getBlock().getType() == Material.BARRIER) {
                loc.getBlock().setType(Material.AIR);
            }
            blockToModelMap.remove(loc);
        }

        for (UUID displayId : activeBlock.displayEntities()) {
            Entity display = findEntityForcefully(activeBlock.origin().getWorld(), displayId);
            if (display != null) {
                display.remove();
            }
        }

        if (persist) {
            saveAsync();
        }
    }

    private Entity findEntityForcefully(World world, UUID id) {
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) return entity;

        for (Entity e : world.getEntities()) {
            if (e.getUniqueId().equals(id)) return e;
        }
        return null;
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

    private void restoreSpawnedModels() {
        if (persistence == null) {
            return;
        }

        for (VirtualBlockSnapshot record : persistence.loadAllVirtualBlocks()) {
            World world = Bukkit.getWorld(record.world());
            if (world == null) {
                continue;
            }

            VirtualModel model = getModel(record.modelName());
            if (model == null) {
                continue;
            }

            Location origin = new Location(world, record.x(), record.y(), record.z());
            Quaternionf rotation = new Quaternionf(record.rotationX(), record.rotationY(), record.rotationZ(), record.rotationW());
            UUID instanceId = record.id();

            Set<Location> expectedBarriers = computeBarrierLocations(model, origin, rotation);
            List<UUID> existingDisplays = record.displayEntities();
            if (existingDisplays == null || existingDisplays.isEmpty()) {
                existingDisplays = findDisplayEntities(world, instanceId);
            }

            Set<Location> existingBarriers = new HashSet<>();
            for (Location barrier : expectedBarriers) {
                if (barrier.getBlock().getType() == Material.BARRIER) {
                    existingBarriers.add(barrier);
                }
            }

            if (!existingDisplays.isEmpty() || !existingBarriers.isEmpty()) {
                for (Location barrier : existingBarriers) {
                    blockToModelMap.put(barrier, instanceId);
                }
                activeBlocks.put(instanceId, new ActiveVirtualBlock(
                        model.name(),
                        origin,
                        rotation,
                        record.cubeEntities() == null ? new HashMap<>() : new HashMap<>(record.cubeEntities()),
                        existingDisplays,
                        existingBarriers
                ));
                continue;
            }

            VirtualBlockPlacementHelper.PlacementData placement =
                    new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
            spawnModel(model, placement, false, instanceId);
        }
    }

    public void saveNow() {
        if (persistence != null) {
            saveAsync();
            persistence.flushNow();
        }
    }

    private Set<Location> computeBarrierLocations(VirtualModel model, Location origin, Quaternionf rotation) {
        Set<Location> locations = new HashSet<>();
        Set<Vector> occupiedOffsets = VirtualBoundingBox.calculateVoxelOffsets(model, rotation, DISPLAY_OFFSET);
        for (Vector offset : occupiedOffsets) {
            locations.add(origin.clone().add(offset));
        }
        return locations;
    }

    private List<UUID> findDisplayEntities(World world, UUID instanceId) {
        String tag = DISPLAY_TAG_PREFIX + instanceId;
        List<UUID> ids = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof BlockDisplay)) {
                continue;
            }
            if (entity.getScoreboardTags().contains(tag)) {
                ids.add(entity.getUniqueId());
            }
        }
        return ids;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
