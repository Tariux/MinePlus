package com.mineplus.infrastructure.virtual.display.nms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The ONLY seam between the display transport and net.minecraft.
 *
 * <p>All packets are handed around as opaque {@code Object}s. The transport works on
 * real-but-never-spawned Bukkit {@link org.bukkit.entity.Display} entities (created
 * through {@code World#createEntity}); this adapter serialises their current state
 * into clientbound packets. Implementations resolve classes by name at runtime, so
 * the core compiles against the plain Bukkit API.</p>
 */
public interface NmsAdapter {

    /** Human readable version tag for logs, e.g. "1.21.1 (spigot-mapped)". */
    String version();

    /** ClientboundAddEntityPacket built from the entity's current position / rotation. */
    Object spawnPacket(Entity entity);

    /**
     * ClientboundSetEntityDataPacket.
     * @param onlyDirty true  -> {@code packDirty()}  (CLEARS the dirty flags - call once per tick!)
     *                  false -> {@code getNonDefaultValues()} (full state, used for spawns / resyncs)
     * @return the packet or {@code null} if there is nothing to send
     */
    Object metadataPacket(Entity entity, boolean onlyDirty);

    /** Absolute position + rotation sync (ClientboundTeleportEntityPacket). */
    Object teleportPacket(Entity entity);

    /** Rotation-only delta (ClientboundMoveEntityPacket.Rot) - 4 bytes of payload instead of 26. */
    Object rotationPacket(Entity entity);

    Object removePacket(int... entityIds);

    /** ClientboundSetPassengersPacket for an arbitrary vehicle id (glues hand items to players). */
    Object passengersPacket(int vehicleId, int... passengerIds);

    /** ClientboundSetEquipmentPacket - hides the vanilla held item for other viewers. */
    Object equipmentPacket(int entityId, EquipmentSlot slot, ItemStack stack);

    /** Wraps packets into one bundle packet: the client applies them atomically in one frame. */
    Object bundle(List<Object> packets);

    void send(Player player, Object packet);

    /** Moves the server-side (never ticked) entity so packets built afterwards reflect the new pos/rot. */
    void setPositionAndRotation(Entity entity, double x, double y, double z, float yaw, float pitch);
}
