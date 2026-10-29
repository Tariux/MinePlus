package com.mineplus.infrastructure.mob.custom;

/**
 * One texture-mapped box of a custom mob part, in vanilla model space.
 *
 * <p>Coordinates are pixels in the same space the vanilla entity model uses:
 * the entity's feet are at {@code y = modelHeightPx}, {@code y} grows downward,
 * and the model's visual front is {@code -z}. A box's dimensions are derived
 * from {@code from}/{@code to}; {@code textureU}/{@code textureV} is the
 * vanilla {@code texOffs(u, v)} origin and implies the standard six-face box
 * layout (down, up, east, north, west, south).</p>
 */
public record MobBox(
        float fromX,
        float fromY,
        float fromZ,
        float toX,
        float toY,
        float toZ,
        int textureU,
        int textureV
) {

    public MobBox {
        if (toX <= fromX || toY <= fromY || toZ <= fromZ) {
            throw new IllegalArgumentException("MobBox requires positive extents: from="
                    + fromX + "," + fromY + "," + fromZ + " to=" + toX + "," + toY + "," + toZ);
        }
        if (textureU < 0 || textureV < 0) {
            throw new IllegalArgumentException("MobBox texture offsets must be non-negative");
        }
    }

    /** Width in pixels (the vanilla {@code w} of a box). */
    public float width() {
        return toX - fromX;
    }

    /** Height in pixels (the vanilla {@code h} of a box). */
    public float height() {
        return toY - fromY;
    }

    /** Depth in pixels (the vanilla {@code d} of a box). */
    public float depth() {
        return toZ - fromZ;
    }

    public static Builder builder(int textureU, int textureV) {
        return new Builder(textureU, textureV);
    }

    public static final class Builder {

        private final int textureU;
        private final int textureV;
        private float fromX;
        private float fromY;
        private float fromZ;
        private float toX;
        private float toY;
        private float toZ;
        private boolean fromSet;
        private boolean toSet;

        private Builder(int textureU, int textureV) {
            this.textureU = textureU;
            this.textureV = textureV;
        }

        /** Minimum corner, in vanilla model pixels (y grows downward). */
        public Builder from(float x, float y, float z) {
            this.fromX = x;
            this.fromY = y;
            this.fromZ = z;
            this.fromSet = true;
            return this;
        }

        /** Maximum corner, in vanilla model pixels (exclusive). */
        public Builder to(float x, float y, float z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            this.toSet = true;
            return this;
        }

        public MobBox build() {
            if (!fromSet || !toSet) {
                throw new IllegalStateException("MobBox requires both from(...) and to(...)");
            }
            return new MobBox(fromX, fromY, fromZ, toX, toY, toZ, textureU, textureV);
        }
    }
}
