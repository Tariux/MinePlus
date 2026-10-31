package com.mineplus.infrastructure.mob.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.EntityType;

/**
 * Visual definition of a custom mob: one texture sheet plus the parts that
 * render it.
 *
 * <p>Two construction paths cover the two phases of custom content:</p>
 * <ul>
 *   <li>{@link #vanilla(EntityType, byte[])} — reuse a vanilla model exactly
 *       and swap only the texture (the Evil Pig phase). No model authoring,
 *       no client-side model file, and the mob keeps the vanilla hitbox.</li>
 *   <li>{@link #builder(int, int, byte[])} — author arbitrary parts for a
 *       completely new silhouette, or start from a vanilla descriptor and
 *       modify it. Each part renders as its own display, so different parts
 *       can move independently (head tracking, leg swing).</li>
 * </ul>
 *
 * <p>The PNG is embedded into the generated resource pack; modules never need
 * to place files in the pack by hand. If the pack subsystem is disabled the
 * base entity stays visible as its vanilla type, so behavior remains testable
 * without the visual layer.</p>
 *
 * <p>A model may carry {@link MobAnimation} clips (typically the walk and idle
 * clips of an imported {@code .bbmodel}). Parts form a tree through
 * {@link MobPart#parent()}, so a clip rotates a bone and everything below it,
 * exactly as the authored outliner does.</p>
 */
public final class CustomMobModel {

    private final int textureWidth;
    private final int textureHeight;
    private final int modelHeightPx;
    private final String textureName;
    private final byte[] texturePng;
    private final List<MobPart> parts;
    private final List<MobAnimation> animations;

    private CustomMobModel(int textureWidth, int textureHeight, int modelHeightPx,
                           String textureName, byte[] texturePng, List<MobPart> parts,
                           List<MobAnimation> animations) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.modelHeightPx = modelHeightPx;
        this.textureName = textureName;
        this.texturePng = texturePng.clone();
        this.parts = List.copyOf(parts);
        this.animations = List.copyOf(animations);
    }

    /** Reuses a published vanilla model and substitutes {@code texturePng}. */
    public static CustomMobModel vanilla(EntityType baseType, byte[] texturePng) {
        VanillaMobModels.VanillaMobParts vanilla = VanillaMobModels.require(baseType);
        return new CustomMobModel(vanilla.textureWidth(), vanilla.textureHeight(),
                vanilla.modelHeightPx(), "texture", requirePng(texturePng), vanilla.parts(), List.of());
    }

    /** Authors a model from parts; {@code textureName} defaults to {@code texture}. */
    public static Builder builder(int textureWidth, int textureHeight, byte[] texturePng) {
        return new Builder(textureWidth, textureHeight, texturePng);
    }

    /** Reuses a vanilla descriptor's parts but lets the caller replace/add parts. */
    public static Builder builderFor(EntityType baseType, byte[] texturePng) {
        VanillaMobModels.VanillaMobParts vanilla = VanillaMobModels.require(baseType);
        return new Builder(vanilla.textureWidth(), vanilla.textureHeight(), texturePng)
                .modelHeightPx(vanilla.modelHeightPx())
                .parts(vanilla.parts());
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    /** Model-space ground line in pixels (24 for a standard quadruped). */
    public int modelHeightPx() {
        return modelHeightPx;
    }

    /** Texture file stem, also the reference segment inside the pack. */
    public String textureName() {
        return textureName;
    }

    /** A private copy of the texture PNG bytes. */
    public byte[] texturePng() {
        return texturePng.clone();
    }

    public List<MobPart> parts() {
        return parts;
    }

    /** The authored clips (walk, idle, …); empty when the model has none. */
    public List<MobAnimation> animations() {
        return animations;
    }

    /** The clip named {@code name} (case-insensitive), or {@code null}. */
    public MobAnimation animation(String name) {
        if (name == null) {
            return null;
        }
        for (MobAnimation animation : animations) {
            if (animation.name().equalsIgnoreCase(name)) {
                return animation;
            }
        }
        return null;
    }

    /** True when at least one part actually draws geometry. */
    public boolean hasGeometry() {
        for (MobPart part : parts) {
            if (!part.boxes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static byte[] requirePng(byte[] png) {
        Objects.requireNonNull(png, "texturePng");
        if (png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new IllegalArgumentException("Custom mob texture must be a PNG file");
        }
        return png;
    }

    public static final class Builder {

        private final int textureWidth;
        private final int textureHeight;
        private final byte[] texturePng;
        private int modelHeightPx = 24;
        private String textureName = "texture";
        private final List<MobPart> parts = new ArrayList<>();
        private final List<MobAnimation> animations = new ArrayList<>();

        private Builder(int textureWidth, int textureHeight, byte[] texturePng) {
            if (textureWidth <= 0 || textureHeight <= 0) {
                throw new IllegalArgumentException("Texture dimensions must be positive");
            }
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.texturePng = requirePng(texturePng);
        }

        public Builder modelHeightPx(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("modelHeightPx must be positive");
            }
            this.modelHeightPx = value;
            return this;
        }

        /** Texture file stem ({@code [a-z0-9_-]}); the pack path becomes {@code mob/<id>/<name>.png}. */
        public Builder textureName(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || !normalized.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid texture name: '" + value + "'");
            }
            this.textureName = normalized;
            return this;
        }

        public Builder part(MobPart part) {
            parts.add(Objects.requireNonNull(part, "part"));
            return this;
        }

        public Builder parts(List<MobPart> value) {
            if (value != null) {
                parts.addAll(value);
            }
            return this;
        }

        public Builder animation(MobAnimation value) {
            if (value != null) {
                animations.add(value);
            }
            return this;
        }

        public Builder animations(List<MobAnimation> value) {
            if (value != null) {
                for (MobAnimation animation : value) {
                    animation(animation);
                }
            }
            return this;
        }

        public CustomMobModel build() {
            if (parts.isEmpty()) {
                throw new IllegalStateException("Custom mob model needs at least one part");
            }
            return new CustomMobModel(textureWidth, textureHeight, modelHeightPx,
                    textureName, texturePng, parts, animations);
        }
    }
}
