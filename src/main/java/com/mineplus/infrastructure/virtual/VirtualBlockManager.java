// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockManager.java
package com.mineplus.infrastructure.virtual;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final Map<Location, UUID> blockToModelMap = new HashMap<>();
    private final Map<UUID, ActiveVirtualBlock> activeBlocks = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private File storageFile;
    private JavaPlugin plugin;
    private boolean debugLoggingEnabled;

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
        this.storageFile = new File(plugin.getDataFolder(), "virtual-blocks.json");
        loadModelDefinitions();
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

    public void shutdown() {
        saveNow();
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
        if (persist) {
            saveNow();
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
            Entity display = org.bukkit.Bukkit.getEntity(displayId);
            if (display != null) {
                display.remove();
            }
        }

        if (persist) {
            saveNow();
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

    private void restoreSpawnedModels() {
        if (storageFile == null || !storageFile.exists()) {
            return;
        }

        Type listType = new TypeToken<List<StoredVirtualBlock>>() {
        }.getType();
        try (FileReader reader = new FileReader(storageFile)) {
            List<StoredVirtualBlock> records = gson.fromJson(reader, listType);
            if (records == null) {
                return;
            }

            for (StoredVirtualBlock record : records) {
                World world = org.bukkit.Bukkit.getWorld(record.world());
                if (world == null) {
                    continue;
                }

                VirtualModel model = getModel(record.modelName());
                if (model == null) {
                    continue;
                }

                Location origin = new Location(world, record.x(), record.y(), record.z());
                Quaternionf rotation = new Quaternionf(record.rotationX(), record.rotationY(), record.rotationZ(), record.rotationW());
                UUID instanceId = parseUuid(record.id());
                if (instanceId == null) {
                    instanceId = UUID.randomUUID();
                }

                Set<Location> expectedBarriers = computeBarrierLocations(model, origin, rotation);
                List<UUID> existingDisplays = findDisplayEntities(world, instanceId);
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
                            existingDisplays,
                            existingBarriers
                    ));
                    continue;
                }

                VirtualBlockPlacementHelper.PlacementData placement =
                        new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
                spawnModel(model, placement, false, instanceId);
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void saveNow() {
        if (storageFile == null) {
            return;
        }

        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }

        List<StoredVirtualBlock> records = new ArrayList<>();
        for (Map.Entry<UUID, ActiveVirtualBlock> entry : activeBlocks.entrySet()) {
            ActiveVirtualBlock active = entry.getValue();
            Location location = active.origin();
            if (location.getWorld() == null) {
                continue;
            }
            records.add(new StoredVirtualBlock(
                    entry.getKey().toString(),
                    active.modelName(),
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ(),
                    active.rotation().x,
                    active.rotation().y,
                    active.rotation().z,
                    active.rotation().w
            ));
        }

        try (FileWriter writer = new FileWriter(storageFile)) {
            gson.toJson(records, writer);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private Set<Location> computeBarrierLocations(VirtualModel model, Location origin, Quaternionf rotation) {
        Set<Location> locations = new HashSet<>();
        VirtualBoundingBox box = VirtualBoundingBox.calculate(model);
        for (Vector offset : box.getOccupiedOffsets()) {
            Vector3f rotatedOffset = new Vector3f((float) offset.getX(), (float) offset.getY(), (float) offset.getZ());
            rotation.transform(rotatedOffset);
            locations.add(origin.clone().add(
                    Math.round(rotatedOffset.x),
                    Math.round(rotatedOffset.y),
                    Math.round(rotatedOffset.z)
            ));
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

    private record StoredVirtualBlock(
            String id,
            String modelName,
            String world,
            int x,
            int y,
            int z,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW
    ) {
    }
}
