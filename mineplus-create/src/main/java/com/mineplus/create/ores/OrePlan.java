package com.mineplus.create.ores;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java ore generation math (no Bukkit, unit-testable).
 *
 * <p>Wilderness-only rule: chunks near a settlement (spawn or any claimed
 * chunk) never generate ore veins; the populator passes a
 * {@code isWilderness} flag per chunk.</p>
 */
public final class OrePlan {

    public static final String ZINC = "mc_zinc_ore";
    public static final String BAUXITE = "mc_bauxite_ore";
    public static final String ALUMINUM = "mc_aluminum_ore";

    /** One vein plan. */
    public static final class Vein {
        public final String ore;
        public final int x;
        public final int y;
        public final int z;
        public final int size;

        Vein(String ore, int x, int y, int z, int size) {
            this.ore = ore;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
        }
    }

    /** Veins per chunk per ore (expected count at the rarity roll). */
    static final int VEINS_PER_CHUNK = 2;

    /**
     * Plans the veins for one chunk.
     *
     * @param chunkX chunk x (for deterministic seeded randomness by caller)
     * @param chunkZ chunk z
     * @param random seeded random
     * @param isWilderness false near settlements/spawn — no veins at all
     */
    public static List<Vein> plan(int chunkX, int chunkZ, Random random, boolean isWilderness) {
        List<Vein> veins = new ArrayList<>();
        if (!isWilderness) {
            return veins;
        }
        String[] ores = {ZINC, BAUXITE, ALUMINUM};
        // Y bands: zinc deep, bauxite mid, aluminum high.
        int[] minY = {5, 24, 48};
        int[] maxY = {40, 56, 72};
        for (int i = 0; i < VEINS_PER_CHUNK; i++) {
            int pick = random.nextInt(ores.length);
            int x = chunkX * 16 + random.nextInt(16);
            int z = chunkZ * 16 + random.nextInt(16);
            int y = minY[pick] + random.nextInt(maxY[pick] - minY[pick]);
            int size = 3 + random.nextInt(6);
            veins.add(new Vein(ores[pick], x, y, z, size));
        }
        return veins;
    }
}
