package com.mineplus.infrastructure.mob.custom;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
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
 *       variants). This is the <b>only</b> correct way to reskin a vanilla mob:
 *       rebuilding its geometry from a descriptor renders a messy copy.</li>
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
    private final Map<String, String> animationMap;
    private final Map<String, HandItem> handItems;
    private final FallbackMode fallback;
    private final Double hitboxWidth;
    private final Double hitboxHeight;

    private MobRenderSpec(RenderType type, String sourceName, Supplier<byte[]> modelBytes,
                          Supplier<byte[]> textureBytes, float scale, Map<String, String> animationMap,
                          Map<String, HandItem> handItems, FallbackMode fallback,
                          Double hitboxWidth, Double hitboxHeight) {
        this.type = type == null ? RenderType.NONE : type;
        this.sourceName = sourceName == null || sourceName.isBlank() ? "model" : sourceName;
        this.modelBytes = modelBytes;
        this.textureBytes = textureBytes;
        this.scale = scale <= 0.0f ? 1.0f : scale;
        this.animationMap = animationMap == null ? Map.of() : Map.copyOf(animationMap);
        this.handItems = handItems == null ? Map.of() : Map.copyOf(handItems);
        this.fallback = fallback == null ? FallbackMode.BASE_ENTITY : fallback;
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

    public Double hitboxWidth() {
        return hitboxWidth;
    }

    public Double hitboxHeight() {
        return hitboxHeight;
    }

    /** The variant registry that covers {@code baseType}, or {@code null}. */
    public static String variantRegistryFor(EntityType baseType) {
        if (baseType == null) {
            return null;
        }
        return switch (baseType) {
            case PIG -> "pig_variant";
            case COW -> "cow_variant";
            case CHICKEN -> "chicken_variant";
            case CAT -> "cat_variant";
            case WOLF -> "wolf_variant";
            case FROG -> "frog_variant";
            case SALMON -> "salmon_variant";
            default -> null;
        };
    }

    /** True when this type's variants support a cold/normal model field. */
    public static boolean variantHasModelField(EntityType baseType) {
        return baseType == EntityType.PIG || baseType == EntityType.COW || baseType == EntityType.CHICKEN;
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
        private final Map<String, String> animationMap = new LinkedHashMap<>();
        private final Map<String, HandItem> handItems = new LinkedHashMap<>();
        private FallbackMode fallback = FallbackMode.BASE_ENTITY;
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
                    animationMap, handItems, fallback, hitboxWidth, hitboxHeight);
        }
    }
}
