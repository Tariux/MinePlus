package com.mineplus.infrastructure.core.block;

import java.util.Locale;
import org.bukkit.Material;

/** Tool matching and mining speed for block hardness. */
public final class BlockBreakingRules {

    private BlockBreakingRules() {
    }

    /** @return the tier prefix of a tool material, or {@code "any"} */
    public static String tierOf(Material material) {
        if (material == null || material == Material.AIR) {
            return "any";
        }
        String name = material.name();
        for (String tier : new String[]{"WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"}) {
            if (name.startsWith(tier + "_")) {
                return tier.toLowerCase(Locale.ROOT);
            }
        }
        return "any";
    }

    /** @return the tool class of a material ({@code pickaxe}, {@code axe}, ...) */
    public static String typeOf(Material material) {
        if (material == null || material == Material.AIR) {
            return "hand";
        }
        String name = material.name();
        if (name.equals("SHEARS")) {
            return "shears";
        }
        for (String type : new String[]{"PICKAXE", "AXE", "SHOVEL", "HOE", "SWORD"}) {
            if (name.endsWith("_" + type)) {
                return type.toLowerCase(Locale.ROOT);
            }
        }
        return "hand";
    }

    public static double speed(String tier) {
        return switch (tier == null ? "any" : tier) {
            case "wooden" -> 2.0;
            case "stone" -> 4.0;
            case "copper" -> 5.0;
            case "iron" -> 6.0;
            case "golden" -> 12.0;
            case "diamond" -> 8.0;
            case "netherite" -> 9.0;
            default -> 1.0;
        };
    }

    /**
     * @return a refusal message when the held tool cannot mine the block, or
     *         {@code null} when it may proceed
     */
    public static String refusal(BlockDefinition.Tool required, Material held) {
        String heldType = typeOf(held);
        String heldTier = tierOf(held);
        if (!required.type().equals("any") && !required.type().equals(heldType)) {
            return "Needs a " + required.type() + ".";
        }
        if (!required.tier().equals("any") && rank(heldTier) < rank(required.tier())) {
            return "Needs at least a " + required.tier() + " " + required.type() + ".";
        }
        return null;
    }

    private static int rank(String tier) {
        return switch (tier == null ? "any" : tier) {
            case "wooden" -> 1;
            case "stone" -> 2;
            case "copper" -> 3;
            case "iron" -> 4;
            case "diamond" -> 5;
            case "netherite" -> 6;
            case "golden" -> 2;
            default -> 0;
        };
    }
}
