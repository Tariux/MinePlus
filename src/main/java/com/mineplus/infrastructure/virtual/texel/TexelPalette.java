package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.CubeFace;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Curated vanilla flat-block palette for virtual rendering, powered by
 * perceptually-weighted Oklab color space, Minecraft directional shading compensation,
 * and a strict stretchability classification (only pure flat concretes are stretchable).
 */
public final class TexelPalette {

    private TexelPalette() {
    }

    /**
     * Average measured RGB per entry.
     * Parallel with {@link #MATERIALS} and {@link #STRETCHABLE_FLAGS}.
     */
    private static final int[] RGB = {
            // 16 concretes (Stretchable: pure flat texture)
            207, 213, 214, // WHITE_CONCRETE
            224, 121, 52,  // ORANGE_CONCRETE
            189, 68, 179,  // MAGENTA_CONCRETE
            35, 137, 199,  // LIGHT_BLUE_CONCRETE
            249, 167, 24,  // YELLOW_CONCRETE
            93, 167, 26,   // LIME_CONCRETE
            213, 117, 140, // PINK_CONCRETE
            62, 68, 71,    // GRAY_CONCRETE
            125, 125, 115, // LIGHT_GRAY_CONCRETE
            21, 119, 136,  // CYAN_CONCRETE
            99, 31, 155,   // PURPLE_CONCRETE
            45, 47, 143,   // BLUE_CONCRETE
            97, 60, 33,    // BROWN_CONCRETE
            57, 76, 41,    // GREEN_CONCRETE
            142, 32, 32,   // RED_CONCRETE
            8, 10, 15,     // BLACK_CONCRETE

            // 16 concrete powders (Stretchable: fine matte grain)
            221, 222, 222, // WHITE_CONCRETE_POWDER
            237, 150, 85,  // ORANGE_CONCRETE_POWDER
            213, 101, 202, // MAGENTA_CONCRETE_POWDER
            112, 179, 229, // LIGHT_BLUE_CONCRETE_POWDER
            254, 216, 85,  // YELLOW_CONCRETE_POWDER
            157, 199, 78,  // LIME_CONCRETE_POWDER
            236, 173, 189, // PINK_CONCRETE_POWDER
            126, 131, 133, // GRAY_CONCRETE_POWDER
            185, 187, 187, // LIGHT_GRAY_CONCRETE_POWDER
            93, 160, 173,  // CYAN_CONCRETE_POWDER
            151, 94, 209,  // PURPLE_CONCRETE_POWDER
            92, 110, 196,  // BLUE_CONCRETE_POWDER
            135, 94, 65,   // BROWN_CONCRETE_POWDER
            112, 133, 75,  // GREEN_CONCRETE_POWDER
            196, 76, 65,   // RED_CONCRETE_POWDER
            35, 38, 43,    // BLACK_CONCRETE_POWDER

            // 16 terracottas (NON-stretchable: organic earthen mottled texture)
            209, 178, 161, // WHITE_TERRACOTTA
            161, 83, 37,   // ORANGE_TERRACOTTA
            149, 88, 122,  // MAGENTA_TERRACOTTA
            143, 110, 120, // LIGHT_BLUE_TERRACOTTA
            186, 133, 35,  // YELLOW_TERRACOTTA
            103, 121, 68,  // LIME_TERRACOTTA
            161, 91, 107,  // PINK_TERRACOTTA
            85, 71, 68,    // GRAY_TERRACOTTA
            134, 118, 105, // LIGHT_GRAY_TERRACOTTA
            86, 91, 91,    // CYAN_TERRACOTTA
            119, 72, 87,   // PURPLE_TERRACOTTA
            79, 58, 50,    // BLUE_TERRACOTTA
            77, 51, 36,    // BROWN_TERRACOTTA
            71, 76, 44,    // GREEN_TERRACOTTA
            143, 61, 46,   // RED_TERRACOTTA
            39, 27, 24,    // BLACK_TERRACOTTA

            // Flat pure snow (Stretchable)
            240, 251, 251, // SNOW_BLOCK

            // Detailed minerals & stones (NON-stretchable: 1x1 only)
            19, 14, 34,    // OBSIDIAN
            18, 62, 68,    // WARPED_WART_BLOCK
            108, 109, 102, // TUFF
            223, 224, 220, // CALCITE
            134, 107, 95,  // DRIPSTONE_BLOCK
            141, 104, 78,  // PACKED_MUD
            59, 57, 59,    // MUD
            51, 46, 54,    // POLISHED_BLACKSTONE
            72, 72, 73,    // POLISHED_DEEPSLATE
            158, 158, 158, // SMOOTH_STONE
            160, 166, 179, // CLAY
            154, 106, 89,  // POLISHED_GRANITE
            132, 134, 133, // POLISHED_ANDESITE
            192, 193, 194, // POLISHED_DIORITE
            218, 207, 153, // CUT_SANDSTONE
            190, 102, 33,  // CUT_RED_SANDSTONE
            99, 156, 151,  // PRISMARINE
            51, 91, 75,    // DARK_PRISMARINE
            76, 56, 43     // SOUL_SOIL
    };

    private static final Material[] MATERIALS = {
            Material.WHITE_CONCRETE,
            Material.ORANGE_CONCRETE,
            Material.MAGENTA_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE,
            Material.YELLOW_CONCRETE,
            Material.LIME_CONCRETE,
            Material.PINK_CONCRETE,
            Material.GRAY_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE,
            Material.CYAN_CONCRETE,
            Material.PURPLE_CONCRETE,
            Material.BLUE_CONCRETE,
            Material.BROWN_CONCRETE,
            Material.GREEN_CONCRETE,
            Material.RED_CONCRETE,
            Material.BLACK_CONCRETE,

            Material.WHITE_CONCRETE_POWDER,
            Material.ORANGE_CONCRETE_POWDER,
            Material.MAGENTA_CONCRETE_POWDER,
            Material.LIGHT_BLUE_CONCRETE_POWDER,
            Material.YELLOW_CONCRETE_POWDER,
            Material.LIME_CONCRETE_POWDER,
            Material.PINK_CONCRETE_POWDER,
            Material.GRAY_CONCRETE_POWDER,
            Material.LIGHT_GRAY_CONCRETE_POWDER,
            Material.CYAN_CONCRETE_POWDER,
            Material.PURPLE_CONCRETE_POWDER,
            Material.BLUE_CONCRETE_POWDER,
            Material.BROWN_CONCRETE_POWDER,
            Material.GREEN_CONCRETE_POWDER,
            Material.RED_CONCRETE_POWDER,
            Material.BLACK_CONCRETE_POWDER,

            Material.WHITE_TERRACOTTA,
            Material.ORANGE_TERRACOTTA,
            Material.MAGENTA_TERRACOTTA,
            Material.LIGHT_BLUE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA,
            Material.LIME_TERRACOTTA,
            Material.PINK_TERRACOTTA,
            Material.GRAY_TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA,
            Material.CYAN_TERRACOTTA,
            Material.PURPLE_TERRACOTTA,
            Material.BLUE_TERRACOTTA,
            Material.BROWN_TERRACOTTA,
            Material.GREEN_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.BLACK_TERRACOTTA,

            Material.SNOW_BLOCK,

            Material.OBSIDIAN,
            Material.WARPED_WART_BLOCK,
            Material.TUFF,
            Material.CALCITE,
            Material.DRIPSTONE_BLOCK,
            Material.PACKED_MUD,
            Material.MUD,
            Material.POLISHED_BLACKSTONE,
            Material.POLISHED_DEEPSLATE,
            Material.SMOOTH_STONE,
            Material.CLAY,
            Material.POLISHED_GRANITE,
            Material.POLISHED_ANDESITE,
            Material.POLISHED_DIORITE,
            Material.CUT_SANDSTONE,
            Material.CUT_RED_SANDSTONE,
            Material.PRISMARINE,
            Material.DARK_PRISMARINE,
            Material.SOUL_SOIL
    };

    /**
     * Strict classification: ONLY concretes, powders and snow block are stretchable.
     * All detailed blocks (terracottas, minerals, stones) are strictly 1x1.
     */
    private static final boolean[] STRETCHABLE_FLAGS = new boolean[MATERIALS.length];

    static {
        // 0..15: Concretes (true)
        for (int i = 0; i < 16; i++) STRETCHABLE_FLAGS[i] = true;
        // 16..31: Concrete powders (true)
        for (int i = 16; i < 32; i++) STRETCHABLE_FLAGS[i] = true;
        // 32..47: Terracottas (false)
        for (int i = 32; i < 48; i++) STRETCHABLE_FLAGS[i] = false;
        // 48: Snow block (true)
        STRETCHABLE_FLAGS[48] = true;
        // 49..end: Detailed blocks (false - strictly 1x1)
        for (int i = 49; i < MATERIALS.length; i++) STRETCHABLE_FLAGS[i] = false;
    }

    private static final BlockData[] BLOCK_DATA = new BlockData[MATERIALS.length];

    // Shading factors in vanilla client: UP=1.0, NORTH/SOUTH=0.8, EAST/WEST=0.6, DOWN=0.5
    private static final float[] SHADE_FACTORS = { 1.0f, 0.8f, 0.6f, 0.5f };

    private static final float[][] OKLAB_L = new float[4][MATERIALS.length];
    private static final float[][] OKLAB_A = new float[4][MATERIALS.length];
    private static final float[][] OKLAB_B = new float[4][MATERIALS.length];

    public static final int NEUTRAL_INDEX = 0;

    private static final ConcurrentHashMap<Integer, Integer> MATCH_CACHE = new ConcurrentHashMap<>();
    private static final int MATCH_CACHE_LIMIT = 1 << 16;
    private static final float NEAR_MATCH_DISTANCE_SQ_OKLAB = 0.0028f;

    static {
        float[] lab = new float[3];
        for (int shade = 0; shade < 4; shade++) {
            float factor = SHADE_FACTORS[shade];
            for (int i = 0; i < MATERIALS.length; i++) {
                int r = Math.round(RGB[i * 3] * factor);
                int g = Math.round(RGB[i * 3 + 1] * factor);
                int b = Math.round(RGB[i * 3 + 2] * factor);
                rgbToOklab(r, g, b, lab);
                OKLAB_L[shade][i] = lab[0];
                OKLAB_A[shade][i] = lab[1];
                OKLAB_B[shade][i] = lab[2];
            }
        }
    }

    public static int size() {
        return MATERIALS.length;
    }

    public static Material material(int index) {
        return MATERIALS[index];
    }

    public static String materialName(int index) {
        return MATERIALS[index].name();
    }

    public static boolean isStretchable(int index) {
        if (index < 0 || index >= STRETCHABLE_FLAGS.length) return false;
        return STRETCHABLE_FLAGS[index];
    }

    public static int rgb(int index) {
        return (RGB[index * 3] << 16) | (RGB[index * 3 + 1] << 8) | RGB[index * 3 + 2];
    }

    public static BlockData blockData(int index) {
        BlockData data = BLOCK_DATA[index];
        if (data == null) {
            data = MATERIALS[index].createBlockData();
            BLOCK_DATA[index] = data;
        }
        return data;
    }

    public static int match(int red, int green, int blue) {
        return match(red, green, blue, null, -1, 1.0f);
    }

    public static int match(int red, int green, int blue, int preferredIndex, float tieTolerance) {
        return match(red, green, blue, null, preferredIndex, tieTolerance);
    }

    public static int match(int red, int green, int blue, CubeFace face, int preferredIndex, float tieTolerance) {
        int r = clampChannel(red);
        int g = clampChannel(green);
        int b = clampChannel(blue);
        int shadeLevel = shadeLevelFor(face);

        int cacheKey = (r << 18) | (g << 10) | (b << 2) | shadeLevel;
        Integer cached = MATCH_CACHE.get(cacheKey);
        int best = cached != null ? cached : nearestOklab(r, g, b, shadeLevel);

        if (cached == null) {
            if (MATCH_CACHE.size() >= MATCH_CACHE_LIMIT) {
                MATCH_CACHE.clear();
            }
            MATCH_CACHE.put(cacheKey, best);
        }

        if (preferredIndex < 0 || preferredIndex >= MATERIALS.length || preferredIndex == best) {
            return best;
        }

        float[] targetLab = new float[3];
        rgbToOklab(r, g, b, targetLab);

        float bestDistSq = weightedDistSqOklab(targetLab[0], targetLab[1], targetLab[2], shadeLevel, best);
        float prefDistSq = weightedDistSqOklab(targetLab[0], targetLab[1], targetLab[2], shadeLevel, preferredIndex);

        if (prefDistSq <= NEAR_MATCH_DISTANCE_SQ_OKLAB && prefDistSq <= bestDistSq * tieTolerance) {
            return preferredIndex;
        }
        return best;
    }

    public static float oklabDistance(int indexA, int indexB) {
        if (indexA == indexB) return 0.0f;
        if (indexA < 0 || indexA >= MATERIALS.length || indexB < 0 || indexB >= MATERIALS.length) {
            return Float.MAX_VALUE;
        }
        float dl = OKLAB_L[0][indexA] - OKLAB_L[0][indexB];
        float da = OKLAB_A[0][indexA] - OKLAB_A[0][indexB];
        float db = OKLAB_B[0][indexA] - OKLAB_B[0][indexB];
        return (float) Math.sqrt(2.25f * dl * dl + da * da + db * db);
    }

    private static int nearestOklab(int r, int g, int b, int shadeLevel) {
        float[] target = new float[3];
        rgbToOklab(r, g, b, target);

        int best = 0;
        float bestDistSq = Float.MAX_VALUE;
        for (int i = 0; i < MATERIALS.length; i++) {
            float distSq = weightedDistSqOklab(target[0], target[1], target[2], shadeLevel, i);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /**
     * Highly perceptive Oklab metric: 2.25x weight on Lightness (L) prevents dark tones
     * (like wood/dirt) from incorrectly resolving to bright saturated concrete blocks.
     */
    private static float weightedDistSqOklab(float l, float a, float b, int shadeLevel, int candidateIndex) {
        float dl = l - OKLAB_L[shadeLevel][candidateIndex];
        float da = a - OKLAB_A[shadeLevel][candidateIndex];
        float db = b - OKLAB_B[shadeLevel][candidateIndex];
        return 2.25f * dl * dl + da * da + db * db;
    }

    private static int shadeLevelFor(CubeFace face) {
        if (face == null) return 0;
        return switch (face) {
            case UP -> 0;
            case NORTH, SOUTH -> 1;
            case EAST, WEST -> 2;
            case DOWN -> 3;
        };
    }

    public static void rgbToOklab(int r, int g, int b, float[] out) {
        float rLin = sRgbToLinear(r / 255.0f);
        float gLin = sRgbToLinear(g / 255.0f);
        float bLin = sRgbToLinear(b / 255.0f);

        float l = (float) Math.cbrt(0.4122214708f * rLin + 0.5363325363f * gLin + 0.0514459929f * bLin);
        float m = (float) Math.cbrt(0.2119034982f * rLin + 0.6806995451f * gLin + 0.1073969566f * bLin);
        float s = (float) Math.cbrt(0.0883024619f * rLin + 0.2817188376f * gLin + 0.6299787005f * bLin);

        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s;
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s;
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s;
    }

    private static float sRgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }
}