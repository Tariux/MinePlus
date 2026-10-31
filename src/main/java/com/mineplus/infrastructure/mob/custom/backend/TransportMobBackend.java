package com.mineplus.infrastructure.mob.custom.backend;

import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The packet-path backend: pooled item displays streamed through the Core's
 * {@link DisplayTransport}, with batching, dirty-state suppression and
 * per-viewer LOD. Per-viewer pack visibility rides the transport's
 * viewer channel, so a viewer without the pack never receives the rig's
 * spawn packets at all.
 */
public final class TransportMobBackend implements MobDisplayBackend {

    private static final Matrix4fc IDENTITY = new Matrix4f();

    private final DisplayTransport transport;
    private final String tagPrefix;

    public TransportMobBackend(DisplayTransport transport, String tagPrefix) {
        this.transport = transport;
        this.tagPrefix = tagPrefix == null || tagPrefix.isBlank() ? "mineplus_mobrig:" : tagPrefix;
    }

    @Override
    public boolean available() {
        return transport != null && transport.isRunning();
    }

    @Override
    public MobDisplayHandle spawn(UUID sessionId, Location at, ItemStack stack,
                                  ItemDisplay.ItemDisplayTransform transform,
                                  int interpolationTicks, int teleportTicks) {
        PooledDisplay pooled = transport.beginItemInstance(sessionId, at::getWorld);
        if (pooled == null) {
            return null;
        }
        ItemDisplay display = pooled.asItemDisplay();
        if (stack != null) {
            display.setItemStack(stack);
        }
        display.setItemDisplayTransform(transform == null
                ? ItemDisplay.ItemDisplayTransform.NONE : transform);
        display.addScoreboardTag(tagPrefix + sessionId);
        pooled.moveTo(transport.nms(), at.getX(), at.getY(), at.getZ(), 0.0f, 0.0f);
        pooled.setTransform(new Matrix4f(), 0);
        return new MobDisplayHandle(null, pooled);
    }

    @Override
    public void finishSession(UUID sessionId, Location origin) {
        transport.finishInstance(sessionId, origin, true);
    }

    @Override
    public void pose(MobDisplayHandle handle, Location at, Matrix4f matrix, int interpolationTicks) {
        if (handle == null || handle.pooled() == null || !handle.pooled().isInUse()) {
            return;
        }
        handle.pooled().moveTo(transport.nms(), at.getX(), at.getY(), at.getZ(), 0.0f, 0.0f);
        handle.pooled().setTransform(matrix, Math.max(1, interpolationTicks));
    }

    @Override
    public void setSessionViewerHidden(UUID sessionId, List<MobDisplayHandle> handles,
                                       UUID viewerId, boolean hidden) {
        // The channel is per viewer, not per session: one call covers every
        // mob rig at once, so per-handle bookkeeping would be pure overhead.
        transport.setViewerPackHidden(viewerId, hidden);
    }

    @Override
    public void despawn(MobDisplayHandle handle) {
        // Pooled displays are session-scoped; destroySession returns them all.
    }

    @Override
    public void destroySession(UUID sessionId) {
        transport.removeInstance(sessionId);
    }
}
