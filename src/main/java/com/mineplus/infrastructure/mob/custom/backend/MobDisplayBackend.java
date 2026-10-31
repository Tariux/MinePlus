package com.mineplus.infrastructure.mob.custom.backend;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

/**
 * How a mob rig's displays exist in the world — the {@code DisplayBackend} of
 * the architecture. Two implementations: {@link EntityMobBackend} spawns real
 * {@code ItemDisplay} entities (works everywhere displays do), and
 * {@link TransportMobBackend} streams pooled displays through the Core's
 * {@code DisplayTransport}.
 *
 * <p>All per-viewer logic lives behind {@link #setSessionViewerHidden}: the
 * engine only decides <em>who</em> should see a rig, never <em>how</em> a
 * viewer is hidden. Matrices passed to {@link #pose} must be translation-free
 * — the display is positioned by {@code at} and rotated/scaled by the matrix,
 * so a translation would be applied twice.</p>
 */
public interface MobDisplayBackend {

    /** True when this backend can spawn displays right now. */
    boolean available();

    /**
     * Spawns one display carrying {@code stack} (geometry piece, hand item, or
     * generic fallback cube).
     *
     * @param sessionId          the rig session the display belongs to
     * @param at                 world position of the display origin
     * @param stack              the item to display, or {@code null} for none
     * @param transform          the {@code ItemDisplayTransform} preset
     * @param interpolationTicks client-side transform easing window
     * @param teleportTicks      client-side position smoothing window
     */
    MobDisplayHandle spawn(UUID sessionId, Location at, ItemStack stack,
                           ItemDisplay.ItemDisplayTransform transform,
                           int interpolationTicks, int teleportTicks);

    /** Finishes a session's spawn pass (transport instances become visible here). */
    void finishSession(UUID sessionId, Location origin);

    /** Positions and orients one display. */
    void pose(MobDisplayHandle handle, Location at, Matrix4f matrix, int interpolationTicks);

    /**
     * Shows or hides a whole session for one viewer. The backend owns how —
     * entity hiding on the plain path, the transport's viewer channel on the
     * packet path.
     */
    void setSessionViewerHidden(UUID sessionId, List<MobDisplayHandle> handles,
                                UUID viewerId, boolean hidden);

    /** Removes one display. */
    void despawn(MobDisplayHandle handle);

    /** Removes a whole session (the transport path returns pooled displays here). */
    void destroySession(UUID sessionId);
}
