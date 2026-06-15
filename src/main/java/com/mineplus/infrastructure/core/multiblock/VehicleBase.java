package com.mineplus.infrastructure.core.multiblock;

import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

public abstract class VehicleBase implements MultiBlockHook {

    protected final VirtualBlockManager vbm;
    private static final String SEAT_TAG = "mineplus_vehicle_seat";

    public VehicleBase(VirtualBlockManager vbm) {
        this.vbm = vbm;
    }

    @Override
    public void onInteract(MultiBlockInstance instance, Player actor) {
        if (actor.getVehicle() != null) return;

        Location seatLoc = instance.coordinate().toLocation().add(0.5, 0.5, 0.5);
        ArmorStand seat = (ArmorStand) seatLoc.getWorld().spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        seat.setInvisible(true);
        seat.setMarker(true);
        seat.addScoreboardTag(SEAT_TAG);
        seat.addScoreboardTag("vehicle_" + instance.id());

        seat.addPassenger(actor);
    }

    protected void move(MultiBlockInstance instance, Vector direction, float rotationDegrees) {
        Location current = instance.coordinate().toLocation();
        Location next = current.clone().add(direction);

        Quaternionf currentRot = instance.rotation();
        Quaternionf deltaRot = new Quaternionf().rotateY((float) Math.toRadians(rotationDegrees));
        Quaternionf nextRot = currentRot.mul(deltaRot);

        vbm.transform(instance.renderedModelId(), next, nextRot, 2);

        // Update instance data
        instance.setRotation(nextRot);
        // Note: Full coordinate update requires registry support, for now we animate display
    }

    protected void animateWheels(MultiBlockInstance instance, String wheelCubeName, float rotationAmount) {
        vbm.transformCube(instance.renderedModelId(), wheelCubeName, new Vector3f(0),
                new Quaternionf().rotateX(rotationAmount), 2);
    }
}
