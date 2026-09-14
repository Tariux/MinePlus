package com.mineplus.pack;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.VirtualModel;
import java.util.Locale;

/**
 * How the pack backend lights its display entities (the {@code PACK.LIGHTING}
 * setting). All pack visuals are {@code Display} entities that inherit world
 * light unless a {@code Brightness} override is set; this policy decides when
 * and how to override.
 *
 * <p>The pre-policy behavior was "force full brightness" (block and sky light
 * 15), which made every pack model glow and hid natural world lighting. The
 * default {@link #AUTO} instead applies an override only to genuinely emissive
 * models, using the model's own maximum {@code light_emission} as the block-light
 * floor — so non-emissive models are lit naturally by the world, while glowing
 * models still glow. Per-face emission cannot be represented by a single display
 * entity, so a model's maximum emission is used (the same approximation the
 * one-display pack axis requires).</p>
 *
 * <ul>
 *   <li>{@link #AUTO} (default) — override only when the model has emission; block light = model max emission, sky 15.</li>
 *   <li>{@link #NATURAL} — never override; the model is lit purely by world light (dark in unlit caves).</li>
 *   <li>{@link #EMISSIVE} — always override; block light = model max emission, sky 15 (virtual-engine parity).</li>
 *   <li>{@link #FULLBRIGHT} — always override to (15, 15); the legacy fully-lit behavior.</li>
 * </ul>
 */
public enum PackLighting {

    AUTO,
    NATURAL,
    EMISSIVE,
    FULLBRIGHT;

    /** Whether a brightness override applies to a model whose maximum emission is {@code emission}. */
    public boolean applies(int emission) {
        return switch (this) {
            case NATURAL -> false;
            case AUTO -> emission > 0;
            case EMISSIVE, FULLBRIGHT -> true;
        };
    }

    /** The block-light component of the override (0-15). */
    public int blockLight(int emission) {
        return this == FULLBRIGHT ? 15 : Math.max(0, Math.min(15, emission));
    }

    /** The sky-light component of the override; overrides pin sky light to daylight. */
    public int skyLight() {
        return 15;
    }

    /** The model's maximum per-cube {@code light_emission} (0 when the model has none). */
    public static int maxEmission(VirtualModel model) {
        if (model == null) {
            return 0;
        }
        int max = 0;
        for (BakedCube cube : model.cubes()) {
            if (cube.lightEmission() > max) {
                max = cube.lightEmission();
            }
        }
        return max;
    }

    public static PackLighting fromKey(String key, PackLighting fallback) {
        if (key == null || key.isBlank()) {
            return fallback == null ? AUTO : fallback;
        }
        return switch (key.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "natural", "world", "none" -> NATURAL;
            case "emissive", "emission" -> EMISSIVE;
            case "fullbright", "full_bright", "full", "legacy" -> FULLBRIGHT;
            default -> AUTO;
        };
    }
}
