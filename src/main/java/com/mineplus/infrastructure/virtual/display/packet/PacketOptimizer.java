package com.mineplus.infrastructure.virtual.display.packet;

import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.util.DebugLogger;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Batches every clientbound packet the transport produces during a tick into as few
 * bundle packets per player as the adapter's weight accounting allows, and centralises
 * the dirty / pool aware spawn, delta and despawn primitives.
 *
 * <p>Bundles are filled greedily: a packet joins the current bundle while the bundle
 * stays under both {@code bundleLimit} packets and the configured weight ceiling, so a
 * metadata-heavy packet cannot silently blow the client's payload limit. Flushing is
 * budgeted per tick; players that do not fit the budget keep their queue and are served
 * first on the following tick, so a crowded world degrades by latency instead of by
 * a main-thread stall.</p>
 *
 * <p>Main-thread only (like everything else in the transport).</p>
 */
public final class PacketOptimizer {

    private record Batch(Player player, List<Object> packets) {}

    private final NmsAdapter nms;
    private final int bundleLimit;
    private final int bundleWeightLimit;
    private final long flushBudgetNanos;
    private final com.mineplus.infrastructure.perf.PerfRegistry perf;
    private final Map<UUID, Batch> batches = new LinkedHashMap<>();

    // metrics
    private long queued, bundles, direct, skipped;
    private long sentPackets, sentWeight;
    private int maxPacketWeight;
    private long deferredPlayers;

    public PacketOptimizer(NmsAdapter nms, int bundleLimit) {
        this(nms, bundleLimit, null);
    }

    public PacketOptimizer(NmsAdapter nms, int bundleLimit,
                           com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this(nms, bundleLimit, 4096, 2, perf);
    }

    public PacketOptimizer(NmsAdapter nms, int bundleLimit, int bundleWeightLimit,
                           int flushBudgetMs, com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this.nms = nms;
        this.bundleLimit = Math.max(1, bundleLimit);
        this.bundleWeightLimit = Math.max(1, bundleWeightLimit);
        this.flushBudgetNanos = Math.max(0, flushBudgetMs) * 1_000_000L;
        this.perf = perf;
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

    /**
     * Sends pending batches until the per-tick flush budget runs out; anything left
     * keeps its order and is sent on the next tick. At least one batch is always sent
     * so a backlog can never starve.
     */
    public void flush() {
        if (batches.isEmpty()) return;
        long deadline = flushBudgetNanos > 0 ? System.nanoTime() + flushBudgetNanos : Long.MAX_VALUE;
        Iterator<Batch> it = batches.values().iterator();
        boolean sentAny = false;
        while (it.hasNext()) {
            if (sentAny && System.nanoTime() >= deadline) break;
            Batch b = it.next();
            it.remove();
            send(b);
            sentAny = true;
        }
        int deferred = batches.size();
        if (deferred > 0) {
            deferredPlayers += deferred;
            DebugLogger.debug("PacketOptimizer: flush budget exhausted, " + deferred
                    + " player(s) deferred to the next tick.");
        }
    }

    public void flush(Player player) {
        Batch b = batches.remove(player.getUniqueId());
        if (b != null) send(b);
    }

    private void send(Batch b) {
        List<Object> pending = b.packets();
        Player p = b.player();
        if (pending.isEmpty() || !p.isOnline()) return;
        int weightLimit = perf != null ? perf.effectiveBundleWeightLimit(bundleWeightLimit) : bundleWeightLimit;

        List<Object> group = new ArrayList<>(Math.min(pending.size(), 16));
        int groupWeight = 0;
        int batchWeight = 0;
        int batchBundles = 0;
        for (Object packet : pending) {
            int weight = Math.max(1, nms.packetWeight(packet));
            if (!group.isEmpty() && (group.size() >= bundleLimit || groupWeight + weight > weightLimit)) {
                if (emit(p, group)) batchBundles++;
                group = new ArrayList<>(Math.min(bundleLimit, 16));
                groupWeight = 0;
            }
            group.add(packet);
            groupWeight += weight;
            batchWeight += weight;
            if (weight > maxPacketWeight) maxPacketWeight = weight;
        }
        if (!group.isEmpty() && emit(p, group)) batchBundles++;

        sentPackets += pending.size();
        sentWeight += batchWeight;
        com.mineplus.infrastructure.perf.PerfRegistry registry = perf;
        if (registry != null) {
            registry.recordNetwork(pending.size(), batchBundles, batchWeight);
        }
    }

    /** @return {@code true} when the group went out as a bundle, {@code false} for a direct send. */
    private boolean emit(Player p, List<Object> group) {
        if (group.size() == 1) {                    // a bundle of one is pure overhead
            nms.send(p, group.get(0));
            direct++;
            return false;
        }
        nms.send(p, nms.bundle(new ArrayList<>(group)));
        bundles++;
        return true;
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

    public record Stats(long queued, long bundles, long direct, long skipped,
                        long packets, long weight, double avgWeight, int maxWeight,
                        long deferredPlayers) {}

    public Stats stats() {
        double avgWeight = sentPackets == 0 ? 0.0 : (double) sentWeight / sentPackets;
        return new Stats(queued, bundles, direct, skipped,
                sentPackets, sentWeight, avgWeight, maxPacketWeight, deferredPlayers);
    }
}
