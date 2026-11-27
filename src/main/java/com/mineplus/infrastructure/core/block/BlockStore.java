package com.mineplus.infrastructure.core.block;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Placed-block records persisted as one JSON array. Writes are atomic
 * (temp file then move); a corrupt file is kept as {@code .bak} and the store
 * starts empty rather than blocking startup.
 */
public final class BlockStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final Consumer<String> warn;
    private final Map<UUID, PlacedBlock> records = new LinkedHashMap<>();

    public BlockStore(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn == null ? message -> { } : warn;
    }

    public synchronized void load() {
        records.clear();
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            PlacedBlock[] parsed = GSON.fromJson(reader, PlacedBlock[].class);
            if (parsed == null) {
                return;
            }
            for (PlacedBlock record : parsed) {
                if (record != null && record.instanceId() != null) {
                    records.put(record.instanceId(), record);
                }
            }
        } catch (JsonSyntaxException | IOException broken) {
            warn.accept("blocks_state: cannot read " + file.getName() + ": "
                    + broken.getMessage() + " (kept as .bak, starting empty)");
            try {
                Files.move(file.toPath(), file.toPath().resolveSibling(file.getName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The original stays in place; the store is still usable.
            }
        }
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                warn.accept("blocks_state: cannot create " + parent);
                return;
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(records.values()), writer);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            warn.accept("blocks_state: cannot write " + file.getName() + ": " + failure.getMessage());
        }
    }

    public synchronized void put(PlacedBlock record) {
        records.put(record.instanceId(), record);
    }

    public synchronized PlacedBlock remove(UUID instanceId) {
        return records.remove(instanceId);
    }

    public synchronized PlacedBlock get(UUID instanceId) {
        return records.get(instanceId);
    }

    public synchronized PlacedBlock at(String world, int x, int y, int z) {
        for (PlacedBlock record : records.values()) {
            if (record.world().equals(world) && record.x() == x
                    && record.y() == y && record.z() == z) {
                return record;
            }
        }
        return null;
    }

    public synchronized Collection<PlacedBlock> all() {
        return List.copyOf(records.values());
    }

    public synchronized int size() {
        return records.size();
    }
}
