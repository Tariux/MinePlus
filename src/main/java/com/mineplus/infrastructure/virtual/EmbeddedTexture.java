package com.mineplus.infrastructure.virtual;

import java.util.Locale;

/**
 * One texture embedded in a bbmodel file (base64 {@code source} field): the
 * authored pixels travel with the model, so they survive read-only installs,
 * stream parses and two models embedding a same-named texture with different art.
 *
 * @param name         texture name as authored, without a lowercasing pass
 * @param declaredPath the authored {@code path} / {@code relative_path}, or {@code null}
 * @param png          decoded PNG bytes
 * @param width        authored texture width in pixels (0 when unknown)
 * @param height       authored texture height in pixels (0 when unknown)
 */
public record EmbeddedTexture(String name, String declaredPath, byte[] png, int width, int height) {

    public EmbeddedTexture {
        png = png == null ? null : png.clone();
    }

    /** Lowercased key the importer files embedded textures under. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public byte[] png() {
        return png == null ? null : png.clone();
    }

    public boolean hasPixels() {
        return png != null && png.length > 0;
    }
}
