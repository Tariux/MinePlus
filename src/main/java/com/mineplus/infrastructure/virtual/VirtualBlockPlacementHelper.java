// Path: src/main/java/com/mineplus/infrastructure/virtual/VirtualBlockPlacementHelper.java
package com.mineplus.infrastructure.virtual;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.joml.Quaternionf;

public class VirtualBlockPlacementHelper {

    /**
     * DTO for holding calculated placement location and model rotation.
     */
    public record PlacementData(Location location, BlockFace attachedFace, Quaternionf globalRotation) {}

    /**
     * Performs a ray trace to determine the exact surface location and orientation for placing a virtual block.
     * 
     * @param player The player attempting to place the block.
     * @param maxDistance Maximum ray trace distance.
     * @return PlacementData containing the target location and required rotation, or null if no valid surface is found.
     */
    public static PlacementData getPlacementData(Player player, double maxDistance) {
        return getPlacementData(player, maxDistance, PlacementOptions.DEFAULT);
    }

    /**
     * Options-aware variant; see {@link PlacementOptions}. Returns null when no valid
     * surface is found or the air-target check rejected the placement point.
     */
    public static PlacementData getPlacementData(Player player, double maxDistance, PlacementOptions options) {
        PlacementOptions opts = options == null ? PlacementOptions.DEFAULT : options;
        RayTraceResult rayTrace = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                maxDistance,
                FluidCollisionMode.NEVER,
                true
        );

        if (rayTrace == null || rayTrace.getHitBlock() == null || rayTrace.getHitBlockFace() == null) {
            return null;
        }

        BlockFace hitFace = rayTrace.getHitBlockFace();
        
        // Offset the placement location by the face normal to ensure surface-level placement
        Location placeLoc = rayTrace.getHitBlock().getLocation().add(hitFace.getDirection());
        if (opts.faceOffset() != 0.0) {
            placeLoc.add(hitFace.getDirection().multiply(opts.faceOffset()));
        }
        if (opts.requireAirTarget() && !placeLoc.getBlock().getType().isAir()) {
            return null;
        }

        Quaternionf rotation = calculateVanillaRotation(player, hitFace, opts.yawSnapDegrees());

        return new PlacementData(placeLoc, hitFace, rotation);
    }

    /**
     * Calculates a quaternion representing vanilla-like block rotation.
     * The rotation mimics items like stairs or observers, snapping to 90-degree increments.
     */
    private static Quaternionf calculateVanillaRotation(Player player, BlockFace hitFace, float snapDegrees) {
        Quaternionf rotation = new Quaternionf();

        // Snap yaw to the configured grid to lock onto the rotation lattice.
        float yaw = player.getLocation().getYaw();
        float snappedYaw = Math.round(yaw / snapDegrees) * snapDegrees;
        
        // Rotate 180 degrees so the front of the model faces the player.
        float yawRad = (float) Math.toRadians(180 - snappedYaw);

        switch (hitFace) {
            case UP -> {
                // Ground placement: Rotate along Y axis only.
                rotation.rotateY(yawRad);
            }
            case DOWN -> {
                // Ceiling placement: Flip upside down (180 deg on X axis) and apply Y rotation.
                rotation.rotateY(yawRad).rotateX((float) Math.PI);
            }
            case NORTH -> {
                // Wall placement (-Z)
                rotation.rotateY(yawRad).rotateX((float) Math.PI / 2).rotateZ((float) Math.PI);
            }
            case SOUTH -> {
                // Wall placement (+Z)
                rotation.rotateY(yawRad).rotateX((float) Math.PI / 2);
            }
            case WEST -> {
                // Wall placement (-X)
                rotation.rotateY(yawRad).rotateX((float) Math.PI / 2).rotateZ((float) Math.PI / 2);
            }
            case EAST -> {
                // Wall placement (+X)
                rotation.rotateY(yawRad).rotateX((float) Math.PI / 2).rotateZ(-(float) Math.PI / 2);
            }
            default -> rotation.rotateY(yawRad);
        }
        return rotation;
    }
}
