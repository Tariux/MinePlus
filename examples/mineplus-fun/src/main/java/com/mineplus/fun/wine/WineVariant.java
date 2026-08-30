package com.mineplus.fun.wine;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The vinery bottle lineup served by the Wine feature, in tasting-flight order
 * ({@code /wine flight} places one instance of every variant, left to right).
 *
 * <p>Every variant ships the same resource triplet — a {@code <key>-wine.bbmodel}
 * converted from the Vinery mod's vanilla {@code java_block} JSON (geometry kept
 * in [0..16] corner space so the Core's AUTO origin detection resolves GRID, the
 * pruned visible cubes matching the strad reference conversion), the matching
 * 16x16 sprite as {@code <key>_wine.png} next to the model for the texel baker,
 * and a {@code <key>-wine.meta.json} opting into texel surface baking.
 *
 * <p>The lineup doubles as a comparison grid for the texel pipeline: each sprite
 * stresses the palette quantizer differently (flat two-tone labels, shaded glass
 * gradients, rotated UV windows), so the side-by-side flight makes bake quality
 * differences visible without commands.
 */
public enum WineVariant {
    STRAD("strad", WineKeys.MACHINE_ID, "Strad Wine Bottle"),
    STAL("stal", WineKeys.MACHINE_ID_STAL, "Stal Wine Bottle"),
    RED("red", WineKeys.MACHINE_ID_RED, "Red Wine Bottle"),
    CHENET("chenet", WineKeys.MACHINE_ID_CHENET, "Chenet Wine Bottle"),
    SOLARIS("solaris", WineKeys.MACHINE_ID_SOLARIS, "Solaris Wine Bottle");

    private final String key;
    private final String typeId;
    private final String displayName;

    WineVariant(String key, String typeId, String displayName) {
        this.key = key;
        this.typeId = typeId;
        this.displayName = displayName;
    }

    /** Lowercase variant name used in commands and resource file names. */
    public String key() {
        return key;
    }

    /** Multiblock type id ({@code wine_bottle*}). */
    public String typeId() {
        return typeId;
    }

    public String displayName() {
        return displayName;
    }

    /** Resolves a variant by command argument (case-insensitive), or {@code null}. */
    public static WineVariant byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (WineVariant variant : values()) {
            if (variant.key.equals(normalized)) {
                return variant;
            }
        }
        return null;
    }

    /** Resolves a variant by multiblock type id, or {@code null} for non-wine types. */
    public static WineVariant byTypeId(String typeId) {
        if (typeId == null) {
            return null;
        }
        for (WineVariant variant : values()) {
            if (variant.typeId.equals(typeId)) {
                return variant;
            }
        }
        return null;
    }

    /** Comma-separated variant keys for usage messages. */
    public static String keyList() {
        return Stream.of(values()).map(WineVariant::key).collect(Collectors.joining(", "));
    }
}
