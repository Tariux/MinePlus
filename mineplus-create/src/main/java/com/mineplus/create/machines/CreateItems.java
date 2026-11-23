package com.mineplus.create.machines;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Recipe item ids to real {@link ItemStack}s. Outputs that have no content item
 * yet ride named vanilla carriers ("mc_wheat_flour" -> Sugar named "Wheat
 * Flour"), so every machine output is holdable and stackable in game without a
 * resource pack. Vanilla inputs ({@code minecraft:*}) map by material id.
 */
public final class CreateItems {

    private CreateItems() {
    }

    /** Builds one output stack for a recipe output id; null when the id is unknown. */
    public static ItemStack output(String itemId, int count) {
        ItemStack stack = carrierFor(itemId);
        if (stack == null) {
            return null;
        }
        stack.setAmount(Math.max(1, Math.min(64, count)));
        return stack;
    }

    /** True when a held stack satisfies one input entry of a recipe. */
    public static boolean matches(String itemId, ItemStack held) {
        if (held == null || held.getType() == Material.AIR) {
            return false;
        }
        Material material = materialFor(itemId);
        return material != null && held.getType() == material;
    }

    private static ItemStack carrierFor(String itemId) {
        return switch (itemId) {
            case "mc_wheat_flour" -> named(Material.SUGAR, "Wheat Flour",  org.bukkit.ChatColor.WHITE);
            case "mc_bonemeal" -> new ItemStack(Material.BONE_MEAL);
            case "mc_sugar" -> new ItemStack(Material.SUGAR);
            case "mc_iron_sheet" -> named(Material.IRON_INGOT, "Iron Sheet", org.bukkit.ChatColor.GRAY);
            case "mc_gold_sheet" -> named(Material.GOLD_INGOT, "Gold Sheet", org.bukkit.ChatColor.YELLOW);
            default -> {
                Material material = materialFor(itemId);
                yield material == null ? null : new ItemStack(material);
            }
        };
    }

    private static Material materialFor(String itemId) {
        if (itemId.startsWith("minecraft:")) {
            return Material.matchMaterial(itemId.substring("minecraft:".length()));
        }
        return null;
    }

    private static ItemStack named(Material material, String name, org.bukkit.ChatColor color) {
        ItemStack stack = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color + name);
        stack.setItemMeta(meta);
        return stack;
    }
}
