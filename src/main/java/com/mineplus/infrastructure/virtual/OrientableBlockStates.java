package com.mineplus.infrastructure.virtual;

import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;

/**
 * Builds oriented/half-cropped {@link BlockData} for the UV alignment layer.
 *
 * <ul>
 *   <li>Directional blocks (furnace, observer...) — {@code facing} set so the front
 *       texture lands on the requested cube face.</li>
 *   <li>Orientable blocks (logs, pillars, quartz) — {@code axis} set so end-grain
 *       (rings) vs side (bark/lines) textures land on the requested face.</li>
 *   <li>Slab blocks — {@code type=TOP/BOTTOM} for half-texture crops: a slab renders
 *       the top or bottom half of its parent block's textures.</li>
 * </ul>
 */
public final class OrientableBlockStates {

    private OrientableBlockStates() {
    }

    private static final Map<CubeFace, org.bukkit.block.BlockFace> FACE_TO_BLOCK_FACE = Map.of(
            CubeFace.NORTH, org.bukkit.block.BlockFace.NORTH,
            CubeFace.SOUTH, org.bukkit.block.BlockFace.SOUTH,
            CubeFace.EAST, org.bukkit.block.BlockFace.EAST,
            CubeFace.WEST, org.bukkit.block.BlockFace.WEST,
            CubeFace.UP, org.bukkit.block.BlockFace.UP,
            CubeFace.DOWN, org.bukkit.block.BlockFace.DOWN
    );

    private static final Set<Material> DIRECTIONAL_TEXTURES = Set.of(
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.DISPENSER, Material.DROPPER, Material.OBSERVER,
            Material.CHEST, Material.ENDER_CHEST, Material.TRAPPED_CHEST,
            Material.BARREL, Material.HOPPER, Material.REPEATER, Material.COMPARATOR,
            Material.CRAFTER, Material.COPPER_BULB
    );

    /**
     * Builds a block state whose directional/orientable texture features align with the
     * requested cube face. Falls back to the material's default state when the material
     * has no meaningful orientation.
     *
     * @param material       the resolved material
     * @param face           the cube face the texture is applied to
     * @param rotationDegrees the face's UV rotation (rotates the facing around Y)
     */
    public static BlockData oriented(Material material, CubeFace face, int rotationDegrees) {
        BlockData data = material.createBlockData();

        if (data instanceof Directional directional && DIRECTIONAL_TEXTURES.contains(material)) {
            org.bukkit.block.BlockFace target = FACE_TO_BLOCK_FACE.get(face);
            if (target != null) {
                target = applyOrientationOffset(target, rotationDegrees);
                if (directional.getFaces().contains(target)) {
                    directional.setFacing(target);
                    return data;
                }
            }
        }

        if (data instanceof Orientable orientable) {
            org.bukkit.Axis axis = switch (face.normalAxis()) {
                case 0 -> org.bukkit.Axis.X;
                case 1 -> org.bukkit.Axis.Y;
                default -> org.bukkit.Axis.Z;
            };
            if (orientable.getAxes().contains(axis)) {
                orientable.setAxis(axis);
                return data;
            }
        }

        return data;
    }

    /**
     * Builds a half-cropped block state: a slab whose type renders exactly the requested
     * vertical half of the parent block's texture.
     *
     * <p>Horizontal halves (LEFT/RIGHT) have no vanilla block-state equivalent; callers
     * rotate the display 90° in-plane to map them onto a vertical half.
     *
     * @return slab block data rendering that half, or null when the material has no slab
     */
    public static BlockData halfCrop(Material material, FaceUvAnalyzer.UvPlan.Half half) {
        Material slabMaterial = slabFor(material);
        if (slabMaterial == null) {
            return null;
        }
        BlockData data = slabMaterial.createBlockData();
        if (data instanceof Slab slab) {
            switch (half) {
                case TOP, LEFT -> slab.setType(Slab.Type.TOP);
                case BOTTOM, RIGHT -> slab.setType(Slab.Type.BOTTOM);
                default -> {
                    return null;
                }
            }
            return data;
        }
        return null;
    }

    private static Material slabFor(Material material) {
        if (material == null) {
            return null;
        }
        Material slab = Material.matchMaterial(material.name() + "_SLAB");
        return slab != null && slab.createBlockData() instanceof Slab ? slab : null;
    }

    private static org.bukkit.block.BlockFace applyOrientationOffset(
            org.bukkit.block.BlockFace base, int degrees) {
        if (degrees == 0 || !isHorizontal(base)) {
            return base;
        }
        java.util.List<org.bukkit.block.BlockFace> ring = java.util.List.of(
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.EAST
        );
        int baseIndex = ring.indexOf(base);
        if (baseIndex < 0) {
            return base;
        }
        int steps = Math.floorDiv(degrees, 90) % 4;
        return ring.get(Math.floorMod(baseIndex + steps, 4));
    }

    private static boolean isHorizontal(org.bukkit.block.BlockFace face) {
        return face == org.bukkit.block.BlockFace.NORTH
                || face == org.bukkit.block.BlockFace.SOUTH
                || face == org.bukkit.block.BlockFace.EAST
                || face == org.bukkit.block.BlockFace.WEST;
    }
}
