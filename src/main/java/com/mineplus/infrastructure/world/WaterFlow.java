package com.mineplus.infrastructure.world;

/**
 * Water-motion rules for machines driven by flowing water.
 *
 * <p>Vanilla water serialises as {@code minecraft:water[level=N]} with level
 * {@code 0} for a source, {@code 1..7} for streaming water and {@code 8} (or
 * higher) for falling water. The rule is kept on plain strings so it can be
 * tested without a running server: a still pool never drives a machine, only
 * water that is actually moving does.</p>
 */
public final class WaterFlow {

    private WaterFlow() {
    }

    /** True when the block-data string describes vanilla water. */
    public static boolean isWater(String blockData) {
        return blockData != null && blockData.startsWith("minecraft:water");
    }

    /**
     * Water level from a block-data string, or {@code -1} when it is not
     * water. A water string without a {@code level} property is a source.
     */
    public static int level(String blockData) {
        if (!isWater(blockData)) {
            return -1;
        }
        int marker = blockData.indexOf("level=");
        if (marker < 0) {
            return 0;
        }
        int index = marker + "level=".length();
        int value = 0;
        boolean digits = false;
        while (index < blockData.length() && Character.isDigit(blockData.charAt(index))) {
            value = value * 10 + (blockData.charAt(index) - '0');
            digits = true;
            index++;
        }
        return digits ? value : 0;
    }

    /**
     * True when a water cell is moving: flowing or falling water
     * ({@code level > 0}), or a still source that is draining into a moving
     * horizontal neighbour. A source in a still pool has no such neighbour
     * and therefore does not drive a wheel.
     *
     * @param level                    the cell's own water level
     * @param horizontalNeighbourLevels levels of the four horizontal
     *                                 neighbours ({@code -1} for non-water)
     */
    public static boolean isFlowing(int level, int[] horizontalNeighbourLevels) {
        if (level < 0) {
            return false;
        }
        if (level > 0) {
            return true;
        }
        if (horizontalNeighbourLevels == null) {
            return false;
        }
        for (int neighbour : horizontalNeighbourLevels) {
            if (neighbour > 0) {
                return true;
            }
        }
        return false;
    }
}
