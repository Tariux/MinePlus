package com.mineplus.infrastructure.virtual.texel;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * The curated vanilla flat-block palette for texel surface baking, with perceptual
 * (redmean) color matching.
 *
 * <p><b>Flatness is a hard membership rule, not a preference.</b> A palette material
 * renders on plates of arbitrary size — a plate stretches the block's texture to its
 * own dimensions. Only materials whose texture noise averages to the perceived color
 * and never reads as a pattern under arbitrary scaling are admitted: stretching a
 * flat material is invisible (a solid color at any size), while stretching a
 * patterned block would distort the pattern and degrade the render. This enforces the
 * authoring rule "texture stretching is only permitted for flat (cement-family)
 * surfaces" by construction: patterned vanilla blocks simply never enter the palette,
 * so no plate can ever stretch a pattern. Colors are always the source texture's own
 * (sampled 1:1 per texel); only the block choice is a nearest-flat-color match.
 *
 * <p>Membership: 16 concretes (saturated backbone), 16 concrete powders (matte, hue
 * fillers), 16 terracottas (muted band; speckle ≤ ±5 RGB converges to the mean),
 * snow block (near-white), obsidian (dark indigo) and warped wart (dark teal) — the
 * two fill the otherwise-missing dark desaturated band so dark glass/shading art
 * quantizes to coherent dark tones instead of cliff-flipping between black and
 * saturated cyan. Glazed terracottas (directional pattern), stained glass
 * (transparency), wools (visible weave), glowstone/sea lantern (mottle/frame
 * patterns), smooth stone (border pattern) and grained materials (planks/logs) are
 * deliberately excluded.
 *
 * <p>Color profiles are the alpha-weighted average RGB of the actual client texture,
 * measured once from the vanilla resource pack — not nominal wiki values. Entries are
 * a fixed parallel table ({@code RGB} triples + {@link Material}s); the matcher is a
 * branch-free full-precision linear scan over the 51 entries with no allocation and
 * no intermediate quantization — source shades that differ by even one RGB unit can
 * resolve to different entries, so distinct texture properties are never merged into
 * one uniform match. The only mutable state is the lazily created shared
 * {@link BlockData} cache.
 */
public final class TexelPalette {

    private TexelPalette() {
    }

    /**
     * Average RGB per entry (alpha-weighted, measured from vanilla 16x16 textures).
     * Order must match {@link #MATERIALS} exactly; a comment per entry is the table.
     */
    private static final int[] RGB = {
            // 16 concretes — saturated, flat, hue-complete backbone
            207, 213, 214, // WHITE_CONCRETE
            224, 121, 52, // ORANGE_CONCRETE
            189, 68, 179, // MAGENTA_CONCRETE
            35, 137, 199, // LIGHT_BLUE_CONCRETE
            249, 167, 24, // YELLOW_CONCRETE
            93, 167, 26, // LIME_CONCRETE
            213, 117, 140, // PINK_CONCRETE
            62, 68, 71, // GRAY_CONCRETE
            125, 125, 115, // LIGHT_GRAY_CONCRETE
            21, 119, 136, // CYAN_CONCRETE
            99, 31, 155, // PURPLE_CONCRETE
            45, 47, 143, // BLUE_CONCRETE
            97, 60, 33, // BROWN_CONCRETE
            57, 76, 41, // GREEN_CONCRETE
            142, 32, 32, // RED_CONCRETE
            8, 10, 15, // BLACK_CONCRETE

            // 16 concrete powders — same hues, matte granular finish; perceived colors
            // sit slightly off the concrete equivalents and fill palette-hull holes
            221, 222, 222, // WHITE_CONCRETE_POWDER
            237, 150, 85, // ORANGE_CONCRETE_POWDER
            213, 101, 202, // MAGENTA_CONCRETE_POWDER
            112, 179, 229, // LIGHT_BLUE_CONCRETE_POWDER
            254, 216, 85, // YELLOW_CONCRETE_POWDER
            157, 199, 78, // LIME_CONCRETE_POWDER
            236, 173, 189, // PINK_CONCRETE_POWDER
            126, 131, 133, // GRAY_CONCRETE_POWDER
            185, 187, 187, // LIGHT_GRAY_CONCRETE_POWDER
            93, 160, 173, // CYAN_CONCRETE_POWDER
            151, 94, 209, // PURPLE_CONCRETE_POWDER
            92, 110, 196, // BLUE_CONCRETE_POWDER
            135, 94, 65, // BROWN_CONCRETE_POWDER
            112, 133, 75, // GREEN_CONCRETE_POWDER
            196, 76, 65, // RED_CONCRETE_POWDER
            35, 38, 43, // BLACK_CONCRETE_POWDER

            // 16 terracottas — muted/desaturated band the concretes do not cover
            209, 178, 161, // WHITE_TERRACOTTA
            161, 83, 37, // ORANGE_TERRACOTTA
            149, 88, 122, // MAGENTA_TERRACOTTA
            143, 110, 120, // LIGHT_BLUE_TERRACOTTA
            186, 133, 35, // YELLOW_TERRACOTTA
            103, 121, 68, // LIME_TERRACOTTA
            161, 91, 107, // PINK_TERRACOTTA
            85, 71, 68, // GRAY_TERRACOTTA
            134, 118, 105, // LIGHT_GRAY_TERRACOTTA
            86, 91, 91, // CYAN_TERRACOTTA
            119, 72, 87, // PURPLE_TERRACOTTA
            79, 58, 50, // BLUE_TERRACOTTA
            77, 51, 36, // BROWN_TERRACOTTA
            71, 76, 44, // GREEN_TERRACOTTA
            143, 61, 46, // RED_TERRACOTTA
            39, 27, 24, // BLACK_TERRACOTTA

            // Gap fillers — near-white and the dark desaturated band: obsidian (dark
            // indigo) and warped wart (dark teal) cover the otherwise-missing dark-blue
            // range, so dark glass/shading art quantizes to coherent dark tones instead
            // of cliff-flipping between black and the saturated cyan entries. All three
            // are visually flat, so they stay stretch-safe.
            240, 251, 251, // SNOW_BLOCK
            19, 14, 34, // OBSIDIAN
            18, 62, 68 // WARPED_WART_BLOCK
    };

    /** Parallel material table; index i corresponds to RGB triple at 3i..3i+2. */
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
            Material.WARPED_WART_BLOCK
    };

    /** Lazily created default block data per entry, shared across all plates. */
    private static final BlockData[] BLOCK_DATA = new BlockData[MATERIALS.length];

    /** Number of palette entries. */
    public static int size() {
        return MATERIALS.length;
    }

    /** Material for a palette index. */
    public static Material material(int index) {
        return MATERIALS[index];
    }

    /** Material name for a palette index (diagnostics). */
    public static String materialName(int index) {
        return MATERIALS[index].name();
    }

    /**
     * Shared default block data for a palette entry. Flat palette materials carry no
     * meaningful orientation, so the default state is exact; the single instance is
     * reused across all rectangles and models.
     */
    public static BlockData blockData(int index) {
        BlockData data = BLOCK_DATA[index];
        if (data == null) {
            data = MATERIALS[index].createBlockData();
            BLOCK_DATA[index] = data;
        }
        return data;
    }

    /**
     * Nearest palette entry for an RGB color, by redmean perceptual distance, at full
     * 8-bit precision. A 51-entry scan per texel is trivially fast at load time, and
     * skipping any intermediate quantization guarantees distinct source colors stay
     * distinct.
     */
    public static int match(int red, int green, int blue) {
        return nearest(clampChannel(red), clampChannel(green), clampChannel(blue));
    }

    /**
     * Nearest palette entry with <i>bounded</i> hysteresis: when the previously used
     * entry is both within {@code tieTolerance} of the best entry's distance <b>and</b>
     * inside the near-exact-match band ({@link #NEAR_MATCH_DISTANCE_SQ}), it is kept.
     * Source art contains runs of near-identical shades jittered by a few RGB units;
     * unbounded hysteresis would let a genuinely different color ride the current run
     * whenever the two candidate distances happen to be close — merging distinct
     * texture regions into one. The absolute cap confines hysteresis to genuine
     * dither/jitter (≤ ~8 RGB units); anything farther always takes its own true
     * nearest entry.
     *
     * @param preferredIndex the previous texel's palette entry, or {@code -1} for none
     * @param tieTolerance   factor &gt; 1; a preferred distance within
     *                       {@code best * tieTolerance} wins, inside the near band
     */
    public static int match(int red, int green, int blue, int preferredIndex, float tieTolerance) {
        int best = match(red, green, blue);
        if (preferredIndex < 0 || preferredIndex >= MATERIALS.length || preferredIndex == best) {
            return best;
        }
        int r = clampChannel(red);
        int g = clampChannel(green);
        int b = clampChannel(blue);
        float bestDistance = distanceSq(r, g, b, best);
        float preferredDistance = distanceSq(r, g, b, preferredIndex);
        boolean nearExactMatch = preferredDistance <= NEAR_MATCH_DISTANCE_SQ;
        return nearExactMatch && preferredDistance <= bestDistance * tieTolerance
                ? preferredIndex : best;
    }

    /**
     * Absolute redmean (squared) distance under which a color counts as a near-exact
     * palette match — the only band where run-continuity hysteresis may engage.
     * Corresponds to roughly 8 RGB units of jitter; genuinely different colors sit at
     * distances in the thousands and never merge.
     */
    private static final float NEAR_MATCH_DISTANCE_SQ = 600.0f;

    /** Redmean perceptual distance (squared) from an RGB color to a palette entry. */
    private static float distanceSq(int r1, int g1, int b1, int index) {
        int r2 = RGB[index * 3];
        int g2 = RGB[index * 3 + 1];
        int b2 = RGB[index * 3 + 2];
        float rm = (r1 + r2) * 0.5f;
        float dr = r1 - r2;
        float dg = g1 - g2;
        float db = b1 - b2;
        return (2.0f + rm / 256.0f) * dr * dr
                + 4.0f * dg * dg
                + (2.0f + (255.0f - rm) / 256.0f) * db * db;
    }

    /**
     * Redmean distance scan over the palette:
     * <pre>{@code
     * r̄ = (r1+r2)/2
     * dist² = (2 + r̄/256)·Δr² + 4·Δg² + (2 + (255−r̄)/256)·Δb²
     * }</pre>
     */
    private static int nearest(int r1, int g1, int b1) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < MATERIALS.length; i++) {
            int r2 = RGB[i * 3];
            int g2 = RGB[i * 3 + 1];
            int b2 = RGB[i * 3 + 2];
            float rm = (r1 + r2) * 0.5f;
            float dr = r1 - r2;
            float dg = g1 - g2;
            float db = b1 - b2;
            float distance = (2.0f + rm / 256.0f) * dr * dr
                    + 4.0f * dg * dg
                    + (2.0f + (255.0f - rm) / 256.0f) * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
