package com.mineplus.infrastructure.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.core.recipes.MachineRecipe;
import com.mineplus.infrastructure.core.recipes.RecipeManager;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class RecipeConfigLoader {

    private final MineplusPlugin plugin;
    private final RecipeManager recipeManager;

    public RecipeConfigLoader(MineplusPlugin plugin, RecipeManager recipeManager) {
        this.plugin = plugin;
        this.recipeManager = recipeManager;
    }

    public void loadAll() {
        recipeManager.clear();

        File folder = new File(plugin.getDataFolder(), "recipes");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Failed to create recipes config folder");
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
            if (root.has("recipes") && root.get("recipes").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("recipes");
                for (int i = 0; i < array.size(); i++) {
                    JsonObject recipe = array.get(i).getAsJsonObject();
                    registerRecipe(recipe);
                }
                return;
            }
            registerRecipe(root);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to load recipe config " + file.getName() + ": " + exception.getMessage());
        }
    }

    private void registerRecipe(JsonObject recipeJson) {
        String id = recipeJson.get("id").getAsString();
        String machine = recipeJson.get("machine").getAsString();
        int level = recipeJson.has("level") ? recipeJson.get("level").getAsInt() : 1;
        int craftTime = recipeJson.has("craftTimeTicks") ? recipeJson.get("craftTimeTicks").getAsInt() : 20;
        Map<String, Integer> input = parseIntMap(recipeJson.getAsJsonObject("input"));
        Map<String, Integer> output = parseIntMap(recipeJson.getAsJsonObject("output"));
        recipeManager.register(new MachineRecipe(id, machine, level, craftTime, input, output));
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
}
