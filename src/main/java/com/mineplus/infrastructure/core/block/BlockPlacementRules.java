package com.mineplus.infrastructure.core.block;

/** Pure placement rules shared by the service and its tests. */
public final class BlockPlacementRules {

    private BlockPlacementRules() {
    }

    /**
     * @param support    definition {@code placement.support}
     * @param faceName   {@link org.bukkit.block.BlockFace#name()} of the clicked face
     * @param belowEmpty whether the cell under the anchor is air
     * @return a refusal message, or {@code null} when the placement is allowed
     */
    public static String supportProblem(String support, String faceName, boolean belowEmpty) {
        if (support == null) {
            return null;
        }
        switch (support) {
            case "ground" -> {
                if ("DOWN".equals(faceName)) {
                    return "Needs to stand on a surface.";
                }
                if (belowEmpty) {
                    return "Needs a solid block underneath.";
                }
            }
            case "wall" -> {
                if ("UP".equals(faceName) || "DOWN".equals(faceName)) {
                    return "Needs a wall to attach to.";
                }
            }
            case "ceiling" -> {
                if (!"DOWN".equals(faceName)) {
                    return "Needs a ceiling to hang from.";
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }
}
