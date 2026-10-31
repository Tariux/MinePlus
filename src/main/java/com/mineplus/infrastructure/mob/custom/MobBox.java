package com.mineplus.infrastructure.mob.custom;

/**
 * One texture-mapped box of a custom mob part, in vanilla model space.
 *
 * <p>Coordinates are pixels relative to the part's pivot in the space the
 * vanilla entity model uses: {@code y} grows downward and the model's visual
 * front is {@code -z}. A box's dimensions are derived from
 * {@code from}/{@code to}; {@code textureU}/{@code textureV} is the vanilla
 * {@code texOffs(u, v)} origin and implies the standard six-face box layout
 * (east, north, west, south, up, down). A box imported with authored per-face
 * windows carries {@link #faceUvs()} instead, and the per-face windows then
 * replace the derived layout.</p>
 *
 * <p>A box may also carry the source model's single-axis element rotation
 * ({@link #rotationAxis()}, {@link #rotationAngle()}). The origin is expressed
 * in the model's own up-positive pixels relative to the part pivot — the same
 * space the client rotates an element about — so an imported spear keeps its
 * authored tilt instead of collapsing to an axis-aligned box.</p>
 */
public record MobBox(
        float fromX,
        float fromY,
        float fromZ,
        float toX,
        float toY,
        float toZ,
        int textureU,
        int textureV,
        MobFaceUvs faceUvs,
        char rotationAxis,
        float rotationAngle,
        float rotationOriginX,
        float rotationOriginY,
        float rotationOriginZ
) {

    public MobBox {
        if (toX <= fromX || toY <= fromY || toZ <= fromZ) {
            throw new IllegalArgumentException("MobBox requires positive extents: from="
                    + fromX + "," + fromY + "," + fromZ + " to=" + toX + "," + toY + "," + toZ);
        }
        if (textureU < 0 || textureV < 0) {
            throw new IllegalArgumentException("MobBox texture offsets must be non-negative");
        }
        if (rotationAxis != 0 && rotationAxis != 'x' && rotationAxis != 'y' && rotationAxis != 'z') {
            throw new IllegalArgumentException("MobBox rotation axis must be x, y or z: " + rotationAxis);
        }
    }

    /** A box whose face windows come from the vanilla box-UV cross layout. */
    public MobBox(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                  int textureU, int textureV) {
        this(fromX, fromY, fromZ, toX, toY, toZ, textureU, textureV, null, (char) 0, 0.0f, 0, 0, 0);
    }

    /** True when the source model rotated this element about one axis. */
    public boolean hasRotation() {
        return rotationAxis != 0 && Math.abs(rotationAngle) > 1.0e-3f;
    }

    /** Whether the box carries authored per-face windows instead of a box-UV origin. */
    public boolean hasFaceUvs() {
        return faceUvs != null;
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
        private MobFaceUvs faceUvs;
        private char rotationAxis;
        private float rotationAngle;
        private float rotationOriginX;
        private float rotationOriginY;
        private float rotationOriginZ;
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

        /** Maximum corner, in vanilla model pixels (y grows downward). */
        public Builder to(float x, float y, float z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            this.toSet = true;
            return this;
        }

        /** Authored per-face windows, in texture pixels (overrides the box-UV layout). */
        public Builder faceUvs(MobFaceUvs value) {
            this.faceUvs = value;
            return this;
        }

        /**
         * The element's single-axis rotation, with its origin in up-positive
         * pixels relative to the part pivot (the source model's own space).
         */
        public Builder rotation(char axis, float angle, float originX, float originY, float originZ) {
            this.rotationAxis = axis;
            this.rotationAngle = angle;
            this.rotationOriginX = originX;
            this.rotationOriginY = originY;
            this.rotationOriginZ = originZ;
            return this;
        }

        public MobBox build() {
            if (!fromSet || !toSet) {
                throw new IllegalStateException("MobBox requires both from(...) and to(...)");
            }
            return new MobBox(fromX, fromY, fromZ, toX, toY, toZ, textureU, textureV, faceUvs,
                    rotationAxis, rotationAngle, rotationOriginX, rotationOriginY, rotationOriginZ);
        }
    }
}
