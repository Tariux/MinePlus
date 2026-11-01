package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.custom.rig.BoneTransformMath;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;

/**
 * How a custom mob is rendered — the {@code render:} block of the
 * architecture: presentation type, the model/texture bytes, rig scale, clip
 * aliases, hand items, the fallback that covers broken or undeliverable
 * models, and an optional hitbox override.
 *
 * <p>Types:</p>
 * <ul>
 *   <li>{@link RenderType#RETEXTURE_VANILLA} — the vanilla base entity keeps
 *       its own geometry; only its texture is swapped (1.21.5+ data-driven
 *       variants). Where the server cannot write a variant the engine falls
 *       back to presenting the same texture on the vanilla part geometry as an
 *       ordinary rig, so the mob still wears its skin.</li>
 *   <li>{@link RenderType#BBMODEL} — a Blockbench {@code .bbmodel} rendered as
 *       a per-bone display rig with its own clips.</li>
 *   <li>{@link RenderType#JAVA_JSON} — a Java block/item model JSON rendered as
 *       one static display following the base entity.</li>
 *   <li>{@link RenderType#NONE} — vanilla look, pack assets still published.</li>
 * </ul>
 */
public final class MobRenderSpec {

    public enum RenderType {
        NONE,
        RETEXTURE_VANILLA,
        BBMODEL,
        JAVA_JSON
    }

    /** What covers the mob when its model cannot be presented. */
    public enum FallbackMode {
        /** The vanilla base entity stays visible (default; gameplay-safe). */
        BASE_ENTITY,
        /** Every bone renders the bundled neutral cube (needs a delivered pack). */
        GENERIC_CUBE
    }

    /**
     * How a mob whose base model is covered by a rig still tells the player it
     * took damage. The rig replaces the vanilla silhouette, so the vanilla red
     * flash has nothing to draw on; the engine restores the feedback instead.
     */
    public enum HurtFeedback {
        /** Nothing extra — the hurt clip alone carries the signal. */
        NONE,
        /** A short red burst at the torso, under the shared particle budget. */
        PARTICLES,
        /** The base model is revealed for the hurt window and flashes red. */
        FLASH
    }

    /** An item (vanilla material or pack item id) hung on a rig bone. */
    public record HandItem(Material material, String packItemId,
                           ItemDisplay.ItemDisplayTransform transform) {

        public HandItem {
            if (material == null && (packItemId == null || packItemId.isBlank())) {
                throw new IllegalArgumentException("A hand item needs a material or a pack item id");
            }
            if (material == null) {
                material = Material.PAPER;
            }
            if (transform == null) {
                transform = ItemDisplay.ItemDisplayTransform.HEAD;
            }
        }

        public static HandItem of(Material material) {
            return new HandItem(material, null, ItemDisplay.ItemDisplayTransform.HEAD);
        }
    }

    private final RenderType type;
    private final String sourceName;
    private final Supplier<byte[]> modelBytes;
    private final Supplier<byte[]> textureBytes;
    private final float scale;
    private final float yawOffsetDegrees;
    private final Map<String, String> animationMap;
    private final Map<String, HandItem> handItems;
    private final FallbackMode fallback;
    private final HurtFeedback hurtFeedback;
    private final Float shadowRadius;
    private final Double hitboxWidth;
    private final Double hitboxHeight;

    private MobRenderSpec(RenderType type, String sourceName, Supplier<byte[]> modelBytes,
                          Supplier<byte[]> textureBytes, float scale, float yawOffsetDegrees,
                          Map<String, String> animationMap,
                          Map<String, HandItem> handItems, FallbackMode fallback,
                          HurtFeedback hurtFeedback, Float shadowRadius,
                          Double hitboxWidth, Double hitboxHeight) {
        this.type = type == null ? RenderType.NONE : type;
        this.sourceName = sourceName == null || sourceName.isBlank() ? "model" : sourceName;
        this.modelBytes = modelBytes;
        this.textureBytes = textureBytes;
        this.scale = scale <= 0.0f ? 1.0f : scale;
        this.yawOffsetDegrees = BoneTransformMath.wrapDegrees(yawOffsetDegrees);
        this.animationMap = animationMap == null ? Map.of() : Map.copyOf(animationMap);
        this.handItems = handItems == null ? Map.of() : Map.copyOf(handItems);
        this.fallback = fallback == null ? FallbackMode.BASE_ENTITY : fallback;
        this.hurtFeedback = hurtFeedback == null ? HurtFeedback.PARTICLES : hurtFeedback;
        this.shadowRadius = shadowRadius;
        this.hitboxWidth = hitboxWidth;
        this.hitboxHeight = hitboxHeight;
    }

    public RenderType type() {
        return type;
    }

    /** The model/texture file name, for logs and reports. */
    public String sourceName() {
        return sourceName;
    }

    /** The model file bytes (bbmodel or java json), or {@code null} for NONE. */
    public byte[] modelBytes() {
        return modelBytes == null ? null : modelBytes.get();
    }

    /**
     * The replacement texture PNG bytes. Required for
     * {@link RenderType#RETEXTURE_VANILLA}; for {@link RenderType#BBMODEL} it is
     * the sibling texture a non-embedded export references.
     */
    public byte[] textureBytes() {
        return textureBytes == null ? null : textureBytes.get();
    }

    /** Uniform rig scale (1.0 = authored size). */
    public float scale() {
        return scale;
    }

    /**
     * Extra yaw applied to the rig's frame, in degrees. A model whose authored
     * front is not Blockbench's {@code -z} (a +z-facing export, a side-facing
     * donor model) needs {@code 180} / {@code ±90} here instead of a renderer
     * change; the value is wrapped into {@code (-180, 180]}.
     */
    public float yawOffsetDegrees() {
        return yawOffsetDegrees;
    }

    /** State → clip name aliases (e.g. {@code attack → animation.smash}). */
    public Map<String, String> animationMap() {
        return animationMap;
    }

    /** Hand items keyed by bone name. */
    public Map<String, HandItem> handItems() {
        return handItems;
    }

    public FallbackMode fallback() {
        return fallback;
    }

    /** How a rigged mob reacts to damage; never {@code null}. */
    public HurtFeedback hurtFeedback() {
        return hurtFeedback;
    }

    /**
     * Radius of the ground shadow plate in blocks. {@code null} derives it from
     * the hitbox width, {@code 0} removes the shadow.
     */
    public Float shadowRadius() {
        return shadowRadius;
    }

    public Double hitboxWidth() {
        return hitboxWidth;
    }

    public Double hitboxHeight() {
        return hitboxHeight;
    }

    public static Builder builder(RenderType type, String sourceName) {
        return new Builder(type, sourceName);
    }

    public static final class Builder {

        private final RenderType type;
        private final String sourceName;
        private Supplier<byte[]> modelBytes;
        private Supplier<byte[]> textureBytes;
        private float scale = 1.0f;
        private float yawOffsetDegrees;
        private final Map<String, String> animationMap = new LinkedHashMap<>();
        private final Map<String, HandItem> handItems = new LinkedHashMap<>();
        private FallbackMode fallback = FallbackMode.BASE_ENTITY;
        private HurtFeedback hurtFeedback = HurtFeedback.PARTICLES;
        private Float shadowRadius;
        private Double hitboxWidth;
        private Double hitboxHeight;

        private Builder(RenderType type, String sourceName) {
            this.type = type == null ? RenderType.NONE : type;
            this.sourceName = sourceName;
        }

        /** Supplies the model file bytes (bbmodel / java json) lazily. */
        public Builder modelBytes(Supplier<byte[]> value) {
            this.modelBytes = value;
            return this;
        }

        /** Supplies the replacement texture PNG bytes lazily. */
        public Builder textureBytes(Supplier<byte[]> value) {
            this.textureBytes = value;
            return this;
        }

        public Builder scale(float value) {
            if (value > 0.0f) {
                this.scale = value;
            }
            return this;
        }

        /** Yaw added to the rig frame; for a model not authored facing {@code -z}. */
        public Builder yawOffsetDegrees(float value) {
            this.yawOffsetDegrees = value;
            return this;
        }

        /** {@code idle → animation.breathe} style clip aliases. */
        public Builder animation(String state, String clipName) {
            if (state != null && clipName != null && !clipName.isBlank()) {
                this.animationMap.put(state.toLowerCase(Locale.ROOT), clipName);
            }
            return this;
        }

        public Builder animationMap(Map<String, String> value) {
            if (value != null) {
                value.forEach(this::animation);
            }
            return this;
        }

        /** Hangs a vanilla material on a bone (right/left hand or any locator). */
        public Builder handItem(String bone, Material material) {
            if (bone != null && !bone.isBlank() && material != null) {
                this.handItems.put(bone.toLowerCase(Locale.ROOT), HandItem.of(material));
            }
            return this;
        }

        /** Hangs a pack item id (or vanilla material name) on a bone. */
        public Builder handItem(String bone, String materialOrItemId) {
            if (bone == null || bone.isBlank() || materialOrItemId == null || materialOrItemId.isBlank()) {
                return this;
            }
            this.handItems.put(bone.toLowerCase(Locale.ROOT), new HandItem(null, materialOrItemId,
                    ItemDisplay.ItemDisplayTransform.HEAD));
            return this;
        }

        public Builder fallback(FallbackMode value) {
            this.fallback = value == null ? FallbackMode.BASE_ENTITY : value;
            return this;
        }

        /** How the mob reacts to damage once its rig covers the vanilla silhouette. */
        public Builder hurtFeedback(HurtFeedback value) {
            this.hurtFeedback = value == null ? HurtFeedback.PARTICLES : value;
            return this;
        }

        /** Ground shadow radius in blocks; {@code 0} removes the shadow. */
        public Builder shadowRadius(float value) {
            this.shadowRadius = Math.max(0.0f, value);
            return this;
        }

        /** Turns the derived ground shadow on or off. */
        public Builder shadow(boolean value) {
            this.shadowRadius = value ? null : Float.valueOf(0.0f);
            return this;
        }

        /** Overrides the base entity's hitbox (blocks; feet-anchored). */
        public Builder hitbox(double width, double height) {
            if (width > 0.0 && height > 0.0) {
                this.hitboxWidth = width;
                this.hitboxHeight = height;
            }
            return this;
        }

        public MobRenderSpec build() {
            if (type == RenderType.BBMODEL || type == RenderType.JAVA_JSON) {
                if (modelBytes == null) {
                    throw new IllegalArgumentException("Render type " + type + " needs modelBytes(...)");
                }
            }
            if (type == RenderType.RETEXTURE_VANILLA && textureBytes == null) {
                throw new IllegalArgumentException("Render type RETEXTURE_VANILLA needs textureBytes(...)");
            }
            return new MobRenderSpec(type, sourceName, modelBytes, textureBytes, scale,
                    yawOffsetDegrees, animationMap, handItems, fallback, hurtFeedback,
                    shadowRadius, hitboxWidth, hitboxHeight);
        }
    }
}
