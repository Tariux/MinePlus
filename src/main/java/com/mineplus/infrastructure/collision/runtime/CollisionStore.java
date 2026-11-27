package com.mineplus.infrastructure.collision.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.collision.fit.BackendType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Durable record of every live model and the technical blocks/entities that
 * belong to it.
 *
 * <p>The whole index is one small JSON file, rewritten atomically after each
 * change. It is the recovery path after a crash or a plugin swap: startup
 * reconciliation reads it and removes anything the world still holds. Access is
 * main-thread only, like every other world-side structure.
 */
public final class CollisionStore {

    private static final int FORMAT = 1;

    private final Path file;
    private final Consumer<String> warn;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final List<PlacedFurniture> furniture = new ArrayList<>();
    private boolean dirty;

    public CollisionStore(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn == null ? message -> {
        } : warn;
    }

    public List<PlacedFurniture> furniture() {
        return furniture;
    }

    public PlacedFurniture byId(UUID id) {
        for (PlacedFurniture entry : furniture) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public void add(PlacedFurniture entry) {
        furniture.add(entry);
        dirty = true;
    }

    public boolean remove(UUID id) {
        boolean removed = furniture.removeIf(entry -> entry.id().equals(id));
        dirty |= removed;
        return removed;
    }

    public void markDirty() {
        dirty = true;
    }

    /** Loads the index; a missing file is an empty store, a broken one is kept aside. */
    public void load() {
        furniture.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            int format = root.has("format") ? root.get("format").getAsInt() : 1;
            if (format > FORMAT) {
                warn.accept("collision store format " + format + " is newer than " + FORMAT
                        + "; ignoring " + file.getFileName());
                return;
            }
            JsonArray entries = root.getAsJsonArray("furniture");
            if (entries != null) {
                for (JsonElement element : entries) {
                    furniture.add(readFurniture(element.getAsJsonObject()));
                }
            }
            dirty = false;
        } catch (IOException | RuntimeException broken) {
            warn.accept("collision store unreadable (" + broken.getMessage() + "); starting empty");
            quarantine();
        }
    }

    /** Writes the index when something changed; failures are reported, never thrown. */
    public void save() {
        if (!dirty || file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        JsonArray entries = new JsonArray();
        for (PlacedFurniture entry : furniture) {
            entries.add(writeFurniture(entry));
        }
        root.add("furniture", entries);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException failure) {
            warn.accept("cannot write collision store " + file.getFileName() + ": "
                    + failure.getMessage());
        }
    }

    private void quarantine() {
        try {
            Path broken = file.resolveSibling(file.getFileName() + ".corrupt");
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            warn.accept("cannot move the broken collision store aside");
        }
    }

    private static JsonObject writeFurniture(PlacedFurniture entry) {
        JsonObject object = new JsonObject();
        object.addProperty("id", entry.id().toString());
        object.addProperty("model", entry.modelKey());
        object.addProperty("world", entry.world());
        object.addProperty("x", entry.originX());
        object.addProperty("y", entry.originY());
        object.addProperty("z", entry.originZ());
        object.addProperty("yaw", entry.yaw());
        object.addProperty("atlas", entry.atlasKey());
        object.addProperty("profile", entry.profileName());
        object.addProperty("resolution", entry.resolution());
        JsonArray parts = new JsonArray();
        for (PlacedPart part : entry.parts()) {
            JsonObject item = new JsonObject();
            item.addProperty("backend", part.backend().name());
            item.addProperty("x", part.cellX());
            item.addProperty("y", part.cellY());
            item.addProperty("z", part.cellZ());
            if (part.state() != null) {
                item.addProperty("state", part.state());
            }
            if (part.originalState() != null) {
                item.addProperty("original", part.originalState());
            }
            if (part.entityType() != null) {
                item.addProperty("entity", part.entityType());
            }
            if (part.entityId() != null) {
                item.addProperty("entityId", part.entityId().toString());
            }
            parts.add(item);
        }
        object.add("parts", parts);
        return object;
    }

    private static PlacedFurniture readFurniture(JsonObject object) {
        PlacedFurniture entry = new PlacedFurniture(
                UUID.fromString(object.get("id").getAsString()),
                object.get("model").getAsString(),
                object.get("world").getAsString(),
                object.get("x").getAsDouble(),
                object.get("y").getAsDouble(),
                object.get("z").getAsDouble(),
                object.has("yaw") ? object.get("yaw").getAsDouble() : 0,
                object.has("atlas") ? object.get("atlas").getAsString() : "",
                object.has("profile") ? object.get("profile").getAsString() : "",
                object.has("resolution") ? object.get("resolution").getAsInt() : 16);
        JsonArray parts = object.getAsJsonArray("parts");
        if (parts != null) {
            for (JsonElement element : parts) {
                JsonObject item = element.getAsJsonObject();
                PlacedPart part = new PlacedPart(
                        BackendType.valueOf(item.get("backend").getAsString()),
                        item.get("x").getAsInt(), item.get("y").getAsInt(),
                        item.get("z").getAsInt(),
                        item.has("state") ? item.get("state").getAsString() : null);
                if (item.has("original")) {
                    part.setOriginalState(item.get("original").getAsString());
                }
                if (item.has("entity")) {
                    part.setEntityType(item.get("entity").getAsString());
                }
                if (item.has("entityId")) {
                    part.setEntityId(UUID.fromString(item.get("entityId").getAsString()));
                }
                entry.add(part);
            }
        }
        return entry;
    }
}
