package com.mineplus.infrastructure.virtual.display.packet;

import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Batches every clientbound packet the transport produces during a tick into a single
 * bundle packet per player, and centralises the dirty / pool aware
 * spawn, delta and despawn primitives.
 *
 * <p>Main-thread only (like everything else in the transport).</p>
 */
public final class PacketOptimizer {

    private record Batch(Player player, List<Object> packets) {}

    private final NmsAdapter nms;
    private final int bundleLimit;
    private final Map<UUID, Batch> batches = new HashMap<>();

    // metrics
    private long queued, bundles, direct, skipped;

    public PacketOptimizer(NmsAdapter nms, int bundleLimit) {
        this.nms = nms;
        this.bundleLimit = bundleLimit;
    }

    // ------------------------------------------------------------------ queueing

    public void queue(Player player, Object packet) {
        if (packet == null || !player.isOnline()) return;
        batches.computeIfAbsent(player.getUniqueId(), k -> new Batch(player, new ArrayList<>(16)))
               .packets().add(packet);
        queued++;
    }

    public void queue(Collection<? extends Player> players, Object packet) {
        if (packet == null) return;
        for (Player p : players) queue(p, packet);
    }

    /** Drops everything pending for a player (quit). */
    public void discard(Player player) {
        batches.remove(player.getUniqueId());
    }

    /** Sends every pending batch. Called exactly once at the end of the transport tick. */
    public void flush() {
        if (batches.isEmpty()) return;
        Iterator<Batch> it = batches.values().iterator();
        while (it.hasNext()) {
            Batch b = it.next();
            it.remove();
            send(b);
        }
    }

    public void flush(Player player) {
        Batch b = batches.remove(player.getUniqueId());
        if (b != null) send(b);
    }

    private void send(Batch b) {
        List<Object> packets = b.packets();
        Player p = b.player();
        if (packets.isEmpty() || !p.isOnline()) return;
        if (packets.size() == 1) {                    // a bundle of one is pure overhead
            nms.send(p, packets.get(0));
            direct++;
            return;
        }
        for (int i = 0; i < packets.size(); i += bundleLimit) {
            List<Object> slice = packets.subList(i, Math.min(packets.size(), i + bundleLimit));
            nms.send(p, nms.bundle(new ArrayList<>(slice)));
            bundles++;
        }
    }

    // ------------------------------------------------------------------ display primitives

    /**
     * Makes {@code viewer} see the display in its CURRENT full state.
     * <ul>
     *   <li>client already knows the id (pool reuse / LOD upgrade): teleport + full metadata</li>
     *   <li>otherwise: spawn + full metadata</li>
     * </ul>
     */
    public void spawnOrResync(Player viewer, PooledDisplay d) {
        if (d.isKnownTo(viewer)) {
            queue(viewer, nms.teleportPacket(d.entity()));
            queue(viewer, nms.metadataPacket(d.entity(), false));
        } else {
            queue(viewer, nms.spawnPacket(d.entity()));
            queue(viewer, nms.metadataPacket(d.entity(), false));
            d.markKnown(viewer);
        }
    }

    /**
     * Ships only what changed since the last call, to every viewer that has the entity.
     * Builds each packet ONCE (packDirty clears vanilla's flags) and clears our flags after.
     */
    public void broadcastDelta(PooledDisplay d, Collection<? extends Player> viewers) {
        DirtyState s = d.dirty();
        Object move = s.isPositionDirty() ? nms.teleportPacket(d.entity())
                    : s.isRotationDirty() ? nms.rotationPacket(d.entity())
                    : null;
        Object meta = nms.metadataPacket(d.entity(), true);   // may be null

        if (move == null && meta == null) {
            skipped++;
            s.clear();
            return;
        }
        for (Player v : viewers) {
            if (!d.isKnownTo(v)) continue;                     // not spawned for them
            queue(v, move);
            queue(v, meta);
        }
        s.clear();
    }

    /**
     * After initial configuration the entity's SynchedEntityData and DirtyState are fully dirty,
     * although every viewer will receive the FULL state via spawnOrResync. Drain both so the
     * first delta does not re-send what the spawn already carried.
     */
    public void markClean(PooledDisplay d) {
        nms.metadataPacket(d.entity(), true);   // packDirty(): result intentionally discarded
        d.dirty().clear();
    }

    /** Removes the given displays from one client (single remove packet with all ids). */
    public void despawn(Player viewer, Collection<PooledDisplay> displays) {
        int[] ids = new int[displays.size()];
        int n = 0;
        for (PooledDisplay d : displays) {
            if (!d.isKnownTo(viewer)) continue;
            ids[n++] = d.id();
            d.forget(viewer.getUniqueId());
        }
        if (n == 0) return;
        queue(viewer, nms.removePacket(n == ids.length ? ids : java.util.Arrays.copyOf(ids, n)));
    }

    // ------------------------------------------------------------------ metrics

    public record Stats(long queued, long bundles, long direct, long skipped) {}

    public Stats stats() {
        return new Stats(queued, bundles, direct, skipped);
    }
}
