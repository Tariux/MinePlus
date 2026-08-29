package com.mineplus.infrastructure.core.items;

import com.mineplus.infrastructure.registry.ItemRegistry;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class InfrastructureItemManager {

    private final ItemRegistry itemRegistry;

    public InfrastructureItemManager(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public ItemStack createItem(String key) {
        ItemStack custom = itemRegistry.createItem(key);
        if (custom != null) {
            return custom;
        }

        Material material = resolveMaterial(key);
        if (material == null || material.isAir()) {
            return null;
        }
        return new ItemStack(material);
    }

    public boolean hasRequirements(Player player, Map<String, Integer> requirement) {
        for (Map.Entry<String, Integer> entry : requirement.entrySet()) {
            int needed = Math.max(entry.getValue(), 0);
            if (needed == 0) {
                continue;
            }

            int found = 0;
            for (ItemStack slot : player.getInventory().getContents()) {
                if (slot == null) {
                    continue;
                }
                if (matches(slot, entry.getKey())) {
                    found += slot.getAmount();
                }
            }
            if (found < needed) {
                return false;
            }
        }
        return true;
    }

    public boolean consumeRequirements(Player player, Map<String, Integer> requirement) {
        if (!hasRequirements(player, requirement)) {
            return false;
        }

        for (Map.Entry<String, Integer> entry : requirement.entrySet()) {
            int remaining = Math.max(entry.getValue(), 0);
            for (int slotIndex = 0; slotIndex < player.getInventory().getSize(); slotIndex++) {
                ItemStack stack = player.getInventory().getItem(slotIndex);
                if (stack == null || remaining <= 0) {
                    continue;
                }
                if (!matches(stack, entry.getKey())) {
                    continue;
                }

                int remove = Math.min(stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() - remove);
                remaining -= remove;
                if (stack.getAmount() <= 0) {
                    player.getInventory().setItem(slotIndex, null);
                } else {
                    player.getInventory().setItem(slotIndex, stack);
                }
            }
        }
        return true;
    }

    private boolean matches(ItemStack itemStack, String requestedKey) {
        String customKey = itemRegistry.readItemKey(itemStack);
        if (customKey != null) {
            return customKey.equalsIgnoreCase(requestedKey);
        }

        Material requiredMaterial = resolveMaterial(requestedKey);
        return requiredMaterial != null && itemStack.getType() == requiredMaterial;
    }

    private Material resolveMaterial(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String normalized = key.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }

        String enumName = normalized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        Material byEnum = Material.matchMaterial(enumName);
        if (byEnum != null) {
            return byEnum;
        }
        return Material.matchMaterial(key);
    }
}
