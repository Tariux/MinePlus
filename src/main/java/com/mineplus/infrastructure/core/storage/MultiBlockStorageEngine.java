package com.mineplus.infrastructure.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.model.BlockCoordinate;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Quaternionf;

public final class MultiBlockStorageEngine {

    private final MineplusPlugin plugin;
    private final File file;
    private final Gson gson;
    private boolean saveQueued;

    public MultiBlockStorageEngine(MineplusPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "multiblocks.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.saveQueued = false;
    }

    public List<MultiBlockInstance> load() {
        if (!file.exists()) {
            return List.of();
        }

        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<StoredMultiBlock>>() {
            }.getType();
            List<StoredMultiBlock> records = gson.fromJson(reader, listType);
            if (records == null) {
                return List.of();
            }

            List<MultiBlockInstance> loaded = new ArrayList<>();
            for (StoredMultiBlock stored : records) {
                loaded.add(stored.toInstance());
            }
            return loaded;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to load multiblocks storage: " + exception.getMessage());
            return List.of();
        }
    }

    public void saveAsync(Iterable<MultiBlockInstance> instances) {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> saveNow(instances), 20L);
    }

    public void saveNow(Iterable<MultiBlockInstance> instances) {
        saveQueued = false;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Failed to create plugin folder for multiblock storage");
            return;
        }

        List<StoredMultiBlock> records = new ArrayList<>();
        for (MultiBlockInstance instance : instances) {
            records.add(StoredMultiBlock.from(instance));
        }

        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(records, writer);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save multiblocks storage: " + exception.getMessage());
        }
    }

    private record StoredMultiBlock(
            String id,
            String typeId,
            String world,
            int x,
            int y,
            int z,
            String owner,
            String creator,
            long createdAt,
            long placedAt,
            int level,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW,
            String renderedModelId,
            Map<String, String> metadata,
            Map<String, String> stateData,
            List<String> linked
    ) {

        static StoredMultiBlock from(MultiBlockInstance instance) {
            Quaternionf rotation = instance.rotation();
            return new StoredMultiBlock(
                    instance.id().toString(),
                    instance.typeId(),
                    instance.coordinate().worldName(),
                    instance.coordinate().x(),
                    instance.coordinate().y(),
                    instance.coordinate().z(),
                    toStringValue(instance.owner()),
                    toStringValue(instance.creator()),
                    instance.createdAt(),
                    instance.placedAt(),
                    instance.level(),
                    rotation.x,
                    rotation.y,
                    rotation.z,
                    rotation.w,
                    toStringValue(instance.renderedModelId()),
                    new LinkedHashMap<>(instance.metadata()),
                    new LinkedHashMap<>(instance.stateData()),
                    instance.linkedBlocks().stream().map(UUID::toString).toList()
            );
        }

        MultiBlockInstance toInstance() {
            Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
            Map<String, String> safeStateData = stateData == null ? Map.of() : stateData;
            List<String> safeLinked = linked == null ? List.of() : linked;

            return new MultiBlockInstance(
                    parseUuid(id),
                    typeId,
                    new BlockCoordinate(world, x, y, z),
                    parseUuid(owner),
                    parseUuid(creator),
                    createdAt,
                    placedAt,
                    level,
                    new Quaternionf(rotationX, rotationY, rotationZ, rotationW),
                    parseUuid(renderedModelId),
                    safeMetadata,
                    safeStateData,
                    safeLinked.stream()
                            .map(StoredMultiBlock::parseUuid)
                            .filter(value -> value != null)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            );
        }

        private static String toStringValue(UUID uuid) {
            return uuid == null ? "" : uuid.toString();
        }

        private static UUID parseUuid(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
