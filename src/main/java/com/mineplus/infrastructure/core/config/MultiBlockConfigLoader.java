package com.mineplus.infrastructure.core.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MultiBlockConfigLoader {

    private final MineplusPlugin plugin;
    private final MultiBlockRegistry registry;
    private final Gson gson;

    public MultiBlockConfigLoader(MineplusPlugin plugin, MultiBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.gson = new Gson();
    }

    public void loadAll() {
        registry.clearTypes();

        File folder = new File(plugin.getDataFolder(), "multiblocks");
        if (!folder.exists() && !folder.mkdirs()) {
            DebugLogger.warning("Failed to create multiblocks config folder");
            return;
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            loadFile(file);
        }
    }

    private void loadFile(File file) {
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String id = root.get("id").getAsString();
            String name = root.has("name") ? root.get("name").getAsString() : id;
            String gui = root.has("gui") ? root.get("gui").getAsString() : "";
            JsonObject levelsObject = root.getAsJsonObject("levels");
            if (levelsObject == null || levelsObject.isEmpty()) {
                DebugLogger.warning("Skipping multiblock without levels: " + file.getName());
                return;
            }

            Map<Integer, MultiBlockLevel> levels = new LinkedHashMap<>();
            for (String levelKey : levelsObject.keySet()) {
                JsonObject levelJson = levelsObject.getAsJsonObject(levelKey);
                int level = Integer.parseInt(levelKey);
                String model = levelJson.has("model") ? levelJson.get("model").getAsString() : "";
                double speed = levelJson.has("speed") ? levelJson.get("speed").getAsDouble() : 1.0D;
                double durability = levelJson.has("durability") ? levelJson.get("durability").getAsDouble() : 1.0D;
                Map<String, Integer> upgradeCost = parseIntMap(levelJson.getAsJsonObject("upgradeCost"));
                Map<String, String> guiOptions = parseStringMap(levelJson.getAsJsonObject("guiOptions"));
                levels.put(level, new MultiBlockLevel(level, model, speed, durability, upgradeCost, guiOptions));
            }

            registry.registerType(new MultiBlockType(id, name, levels, new MultiBlockHook() {
            }, gui));
        } catch (Exception exception) {
            DebugLogger.warning("Failed to load multiblock config " + file.getName() + ": " + exception.getMessage());
        }
    }

    private Map<String, Integer> parseIntMap(JsonObject object) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (object == null) {
            return map;
        }
        for (String key : object.keySet()) {
            map.put(key, object.get(key).getAsInt());
        }
        return map;
    }

    private Map<String, String> parseStringMap(JsonObject object) {
        Map<String, String> map = new LinkedHashMap<>();
        if (object == null) {
            return map;
        }
        for (String key : object.keySet()) {
            map.put(key, object.get(key).getAsString());
        }
        return map;
    }
}
