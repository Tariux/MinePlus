package com.mineplus.infrastructure.virtual.display.pool;

import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.packet.DirtyState;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One reusable virtual Display entity. The wrapped Bukkit entity was created with
 * {@code World#createEntity} and is never added to the world, so it costs no ticks,
 * no chunk storage and no vanilla tracking - it is a pure metadata container whose
 * state the transport serialises into per-viewer packets.
 */
public final class PooledDisplay {

    private final Display entity;
    private final Set<UUID> knownClients = new HashSet<>();
    private final DirtyState dirty = new DirtyState();
    private final Matrix4f transform = new Matrix4f();
    private boolean inUse;

    PooledDisplay(Display entity) {
        this.entity = entity;
    }

    // ------------------------------------------------------------------ accessors

    public Display entity()               { return entity; }
    public int id()                       { return entity.getEntityId(); }
    public DirtyState dirty()             { return dirty; }
    public Matrix4fc transform()          { return transform; }
    public boolean isInUse()              { return inUse; }
    public BlockDisplay asBlockDisplay()  { return (BlockDisplay) entity; }
    public ItemDisplay asItemDisplay()    { return (ItemDisplay) entity; }

    void setInUse(boolean inUse)          { this.inUse = inUse; }

    // ------------------------------------------------------------------ client bookkeeping

    public boolean isKnownTo(Player p)     { return knownClients.contains(p.getUniqueId()); }
    public void markKnown(Player p)        { knownClients.add(p.getUniqueId()); }
    public void forget(UUID player)        { knownClients.remove(player); }
    public Set<UUID> knownClients()        { return Collections.unmodifiableSet(knownClients); }

    // ------------------------------------------------------------------ state mutation (dirty aware)

    /** Position + rotation. Flags position dirty -> a teleport packet on the next delta. */
    public boolean moveTo(NmsAdapter nms, double x, double y, double z, float yaw, float pitch) {
        boolean p = dirty.updatePosition(x, y, z);
        boolean r = dirty.updateRotation(yaw, pitch);
        if (p || r) nms.setPositionAndRotation(entity, x, y, z, yaw, pitch);
        return p || r;
    }

    /**
     * Rotation only. Position is accepted silently: passengers are positioned by the
     * client, emitting teleports for them would fight the vehicle logic.
     */
    public boolean rotateTo(NmsAdapter nms, double x, double y, double z, float yaw, float pitch) {
        dirty.acceptPosition(x, y, z);
        boolean r = dirty.updateRotation(yaw, pitch);
        nms.setPositionAndRotation(entity, x, y, z, yaw, pitch);
        return r;
    }

    /**
     * Applies a full affine matrix (Bukkit decomposes it into translation / left rotation /
     * scale / right rotation). No-op when the matrix did not change.
     *
     * @param interpolationTicks 0 = snap, >0 = client-side blend over N ticks
     */
    public boolean setTransform(Matrix4fc matrix, int interpolationTicks) {
        if (!dirty.updateTransform(matrix)) return false;
        transform.set(matrix);
        if (interpolationTicks > 0) {
            entity.setInterpolationDuration(interpolationTicks);
            // Force the "start delta ticks" data value to be re-sent; setting the
            // same duration twice would not mark the watcher entry dirty.
            entity.setInterpolationDelay(1);
            entity.setInterpolationDelay(0);
        } else {
            entity.setInterpolationDuration(0);
        }
        entity.setTransformationMatrix(new Matrix4f(matrix));
        return true;
    }

    /** Back-to-pool reset: renders nothing on clients that still hold the id. */
    void reset() {
        transform.identity();
        dirty.reset();
        entity.setTransformationMatrix(new Matrix4f());
        entity.setInterpolationDuration(0);
        entity.setInterpolationDelay(0);
        setBrightnessSafe(null);
        entity.setGlowing(false);
        if (entity instanceof BlockDisplay bd) bd.setBlock(Material.AIR.createBlockData());
        if (entity instanceof ItemDisplay id) {
            try {
                id.setItemStack(null);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // null not accepted on this runtime - the entity stays without a stack
            }
        }
    }

    private void setBrightnessSafe(org.bukkit.entity.Display.Brightness brightness) {
        try {
            entity.setBrightness(brightness);
        } catch (NullPointerException ignored) {
            // some runtimes reject null; the reset path is best-effort
        }
    }
}
