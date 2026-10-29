package com.mineplus.infrastructure.mob.custom;

import java.util.List;
import java.util.Objects;
import org.bukkit.entity.EntityType;

/**
 * Vanilla entity model descriptors available to texture-only custom mobs.
 *
 * <p>Each descriptor is the exact part layout of a vanilla model: pivots,
 * base rotations, box extents and box UV origins. A custom mob reuses that
 * geometry and supplies its own texture, so it looks like a reskinned vanilla
 * mob without a client model of its own.</p>
 *
 * <p>Only models whose part layout is stable and quadruped-shaped are listed.
 * A future entity type is added here once its geometry is copied from the
 * vanilla model, which is a data change, not a renderer change. Models that
 * need bones, hierarchical transforms or animation clips are expressed through
 * {@link CustomMobModel#builder(int, int, byte[])} instead.</p>
 */
public final class VanillaMobModels {

    /** One vanilla model: texture sheet size, model height and parts. */
    public record VanillaMobParts(int textureWidth, int textureHeight, int modelHeightPx,
                                  List<MobPart> parts) {

        public VanillaMobParts {
            if (textureWidth <= 0 || textureHeight <= 0 || modelHeightPx <= 0) {
                throw new IllegalArgumentException("Vanilla mob model dimensions must be positive");
            }
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("Vanilla mob model needs at least one part");
            }
        }
    }

    /**
     * The vanilla pig: a 64x32 sheet, 24 px tall, with the standard quadruped
     * head (with snout), body and four legs. The body's vanilla 90-degree X
     * rotation is preserved as the part's base rotation.
     */
    public static final VanillaMobParts PIG = new VanillaMobParts(64, 32, 24, List.of(
            MobPart.builder("head", MobPart.Role.HEAD)
                    .pivot(0, 12, -6)
                    .box(MobBox.builder(0, 0).from(-4, -4, -8).to(4, 4, 0).build())
                    .box(MobBox.builder(16, 16).from(-2, 0, -9).to(2, 3, -8).build())
                    .build(),
            MobPart.builder("body", MobPart.Role.BODY)
                    .pivot(0, 11, 2)
                    .rotation(90, 0, 0)
                    .box(MobBox.builder(28, 8).from(-5, -10, -7).to(5, 6, 1).build())
                    .build(),
            MobPart.builder("right_hind_leg", MobPart.Role.LEG_BACK_RIGHT)
                    .pivot(-3, 18, 7)
                    .box(MobBox.builder(0, 16).from(-2, 0, -2).to(2, 6, 2).build())
                    .build(),
            MobPart.builder("left_hind_leg", MobPart.Role.LEG_BACK_LEFT)
                    .pivot(3, 18, 7)
                    .box(MobBox.builder(0, 16).from(-2, 0, -2).to(2, 6, 2).build())
                    .build(),
            MobPart.builder("right_front_leg", MobPart.Role.LEG_FRONT_RIGHT)
                    .pivot(-3, 18, -5)
                    .box(MobBox.builder(0, 16).from(-2, 0, -2).to(2, 6, 2).build())
                    .build(),
            MobPart.builder("left_front_leg", MobPart.Role.LEG_FRONT_LEFT)
                    .pivot(3, 18, -5)
                    .box(MobBox.builder(0, 16).from(-2, 0, -2).to(2, 6, 2).build())
                    .build()
    ));

    private VanillaMobModels() {
    }

    /** The vanilla descriptor for {@code type}, or {@code null} when none is published. */
    public static VanillaMobParts forType(EntityType type) {
        if (type == EntityType.PIG) {
            return PIG;
        }
        return null;
    }

    /** {@code true} when a texture-only custom mob can reuse this vanilla model. */
    public static boolean supports(EntityType type) {
        return forType(type) != null;
    }

    /** The entity types with a published vanilla descriptor. */
    public static List<EntityType> supportedTypes() {
        return List.of(EntityType.PIG);
    }

    static VanillaMobParts require(EntityType type) {
        VanillaMobParts parts = forType(Objects.requireNonNull(type, "type"));
        if (parts == null) {
            throw new IllegalArgumentException("No vanilla model descriptor for " + type
                    + "; published models: " + supportedTypes());
        }
        return parts;
    }
}
