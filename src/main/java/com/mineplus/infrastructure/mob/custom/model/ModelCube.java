package com.mineplus.infrastructure.mob.custom.model;

import java.util.Map;

/**
 * One box of a parsed model, in bone-local model pixels (y-up, front {@code -z}).
 *
 * <p>Per-face windows are texture pixels; {@code u1 < u2} draws normally,
 * {@code u1 > u2} mirrors — the same convention vanilla model JSON uses, and
 * authored mirroring is copied verbatim. Parsers convert box-UV origins into
 * these windows, so emission never re-derives a layout.</p>
 */
public final class ModelCube {

    /** One face's texture window in texture pixels. */
    public record Uv(float u1, float v1, float u2, float v2) {
    }

    private final float fromX;
    private final float fromY;
    private final float fromZ;
    private final float toX;
    private final float toY;
    private final float toZ;
    private final float inflate;
    private final int textureIndex;
    private final Map<String, Uv> faces;

    public ModelCube(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                     float inflate, int textureIndex, Map<String, Uv> faces) {
        if (toX <= fromX || toY <= fromY || toZ <= fromZ) {
            throw new IllegalArgumentException("ModelCube requires positive extents: from="
                    + fromX + "," + fromY + "," + fromZ + " to=" + toX + "," + toY + "," + toZ);
        }
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toY = toY;
        this.toZ = toZ;
        this.inflate = inflate;
        this.textureIndex = textureIndex;
        this.faces = faces == null ? Map.of() : Map.copyOf(faces);
    }

    public float fromX() {
        return fromX;
    }

    public float fromY() {
        return fromY;
    }

    public float fromZ() {
        return fromZ;
    }

    public float toX() {
        return toX;
    }

    public float toY() {
        return toY;
    }

    public float toZ() {
        return toZ;
    }

    /** Outward growth of every face in model pixels (0 = exact box). */
    public float inflate() {
        return inflate;
    }

    /** Texture index into {@link ModelData#textures()}, or {@code -1} when untextured. */
    public int textureIndex() {
        return textureIndex;
    }

    /** Per-face windows keyed by vanilla face names ({@code north}, {@code south}, …). */
    public Map<String, Uv> faces() {
        return faces;
    }

    /** The window for one vanilla face name, or {@code null}. */
    public Uv face(String direction) {
        return faces.get(direction);
    }

    public float width() {
        return toX - fromX;
    }

    public float height() {
        return toY - fromY;
    }

    public float depth() {
        return toZ - fromZ;
    }
}
