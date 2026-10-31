package com.mineplus.infrastructure.mob.custom.model;

import java.util.Arrays;

/**
 * One texture of a parsed model: its authored size and PNG bytes.
 *
 * <p>{@code png} is {@code null} when the source referenced an external file
 * the parser could not read (a sibling PNG that is not in the jar, a missing
 * base64 payload); validation then repairs it with a neutral placeholder so a
 * broken texture never ships as a missing-model or a magenta cube.</p>
 */
public final class ModelTexture {

    private final String name;
    private final int width;
    private final int height;
    private final byte[] png;
    private final boolean resolved;

    public ModelTexture(String name, int width, int height, byte[] png, boolean resolved) {
        this.name = name == null || name.isBlank() ? "texture" : name;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.png = png == null ? null : Arrays.copyOf(png, png.length);
        this.resolved = resolved;
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The PNG bytes, or {@code null} when unresolved. */
    public byte[] png() {
        return png == null ? null : Arrays.copyOf(png, png.length);
    }

    /** True when the parser actually had pixels for this texture. */
    public boolean isResolved() {
        return resolved;
    }
}
