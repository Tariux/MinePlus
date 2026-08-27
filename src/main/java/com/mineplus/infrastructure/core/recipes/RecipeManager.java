package com.mineplus.infrastructure.core.recipes;

import com.mineplus.infrastructure.core.util.StringNormalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeManager {

    private final Map<String, MachineRecipe> recipes;
    private final Map<String, List<MachineRecipe>> byMachine;

    public RecipeManager() {
        this.recipes = new LinkedHashMap<>();
        this.byMachine = new LinkedHashMap<>();
    }

    public void register(MachineRecipe recipe) {
        String id = normalize(recipe.id());
        MachineRecipe existing = recipes.get(id);
        if (existing != null) {
            List<MachineRecipe> machineRecipes = byMachine.get(normalize(existing.machineTypeId()));
            if (machineRecipes != null) {
                machineRecipes.removeIf(value -> normalize(value.id()).equals(id));
            }
        }
        recipes.put(id, recipe);
        byMachine.computeIfAbsent(normalize(recipe.machineTypeId()), ignored -> new ArrayList<>()).add(recipe);
    }

    public void clear() {
        recipes.clear();
        byMachine.clear();
    }

    public MachineRecipe get(String id) {
        return recipes.get(normalize(id));
    }

    public List<MachineRecipe> getForMachine(String machineTypeId, int level) {
        List<MachineRecipe> candidates = byMachine.getOrDefault(normalize(machineTypeId), List.of());
        List<MachineRecipe> accepted = new ArrayList<>();
        for (MachineRecipe recipe : candidates) {
            if (recipe.minLevel() <= level) {
                accepted.add(recipe);
            }
        }
        return Collections.unmodifiableList(accepted);
    }

    public List<MachineRecipe> all() {
        return Collections.unmodifiableList(new ArrayList<>(recipes.values()));
    }

    public MachineRecipe findMatch(String machineTypeId, int level, Map<String, Integer> providedInput) {
        if (providedInput == null || providedInput.isEmpty()) {
            return null;
        }

        Map<String, Integer> normalizedInput = new HashMap<>();
        for (Map.Entry<String, Integer> entry : providedInput.entrySet()) {
            int amount = Math.max(0, entry.getValue());
            if (amount <= 0) {
                continue;
            }
            normalizedInput.merge(normalize(entry.getKey()), amount, Integer::sum);
        }

        if (normalizedInput.isEmpty()) {
            return null;
        }

        for (MachineRecipe recipe : getForMachine(machineTypeId, level)) {
            if (matchesRequirements(recipe.input(), normalizedInput)) {
                return recipe;
            }
        }
        return null;
    }

    private boolean matchesRequirements(Map<String, Integer> requirements, Map<String, Integer> provided) {
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }

        for (Map.Entry<String, Integer> requirement : requirements.entrySet()) {
            String key = normalize(requirement.getKey());
            int needed = Math.max(0, requirement.getValue());
            int available = provided.getOrDefault(key, 0);
            if (available < needed) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String value) {
        return StringNormalizer.normalize(value);
    }
}
