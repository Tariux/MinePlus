package com.mineplus.infrastructure.virtual.display;

import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.display.nms.NmsAdapter;
import com.mineplus.infrastructure.virtual.display.nms.NmsAdapterFactory;
import com.mineplus.infrastructure.virtual.display.packet.PacketOptimizer;
import com.mineplus.infrastructure.virtual.display.pool.EntityPoolManager;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Packet-based display transport: the backend the virtual rendering pipeline spawns
 * through instead of {@code world.spawn}. Every virtual display is a real Bukkit
 * entity created with {@code World#createEntity} (never added to the world, never
 * ticked, invisible to vanilla tracking) and streamed to viewers through the
 * {@link NmsAdapter} with pooling, dirty-state suppression, bundle batching and
 * per-viewer LOD.
 *
 * <p>The transport is <b>additive</b>: {@code VirtualBlockManager} falls back to the
 * legacy spawned-entity path whenever the transport is disabled or unavailable, so
 * servers keep rendering exactly as before when the packet path is off.</p>
 */
public final class DisplayTransport {

    /** Structured viewer list of one rendered model instance. */
    public static final class RenderedInstance {
        private final UUID id;
        private final List<PooledDisplay> displays = new ArrayList<>();
        private final Map<UUID, LodTier> viewers = new HashMap<>();
        private boolean animated;

        RenderedInstance(UUID id) {
            this.id = id;
        }

        public UUID id()                       { return id; }
        public List<PooledDisplay> displays()  { return displays; }
        public Map<UUID, LodTier> viewers()    { return viewers; }
        public boolean isAnimated()            { return animated; }
        void setAnimated(boolean animated)     { this.animated = animated; }
    }

    public enum LodTier {
        FULL, STATIC, HIDDEN;

        public static LodTier forDistanceSq(double distanceSq, DisplayTransportSettings settings) {
            if (distanceSq <= settings.lodFullRangeSq())   return FULL;
            if (distanceSq <= settings.lodStaticRangeSq()) return STATIC;
            return HIDDEN;
        }
    }

    private final JavaPlugin plugin;
    private final NmsAdapter nms;
    private final EntityPoolManager pool;
    private final PacketOptimizer packets;
    private final DisplayTransportSettings settings;

    private final Map<UUID, RenderedInstance> instances = new HashMap<>();
    private final Map<Long, Set<RenderedInstance>> byChunk = new HashMap<>();

    private final Map<UUID, Boolean> pendingLod = new HashMap<>();          // playerId -> moved flag
    private final Map<UUID, Double> pendingLodDistance = new HashMap<>();   // playerId -> forced distance (teleports)
    private final Set<UUID> pendingFullUpdate = new HashSet<>();            // players whose LOD must run this tick

    private BukkitTask tickTask;
    private boolean running;

    private DisplayTransport(JavaPlugin plugin, DisplayTransportSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.nms = NmsAdapterFactory.create();
        this.packets = new PacketOptimizer(nms, settings.bundleLimit());
        this.pool = new EntityPoolManager(nms, packets, settings.maxIdlePooledPerWorld());
    }

    /**
     * Creates and starts the transport. Throws {@link IllegalStateException} when the
     * runtime NMS surface is unsupported; the caller falls back to the legacy path.
     */
    public static DisplayTransport start(JavaPlugin plugin, DisplayTransportSettings settings) {
        DisplayTransport transport = new DisplayTransport(plugin, settings);
        transport.startTicking();
        DebugLogger.info("[DisplayTransport] started for server " + transport.nms.version()
                + " (lod full=" + settings.lodFullRange() + " static=" + settings.lodStaticRange() + ")");
        return transport;
    }

    public DisplayTransportSettings settings()  { return settings; }
    public EntityPoolManager pool()             { return pool; }
    public PacketOptimizer packets()            { return packets; }
    public NmsAdapter nms()                     { return nms; }
    public boolean isRunning()                  { return running; }

    private void startTicking() {
        running = true;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        running = false;
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (RenderedInstance instance : instances.values()) {
            for (PooledDisplay d : instance.displays()) {
                pool.destroy(d);
            }
        }
        instances.clear();
        byChunk.clear();
        pendingLod.clear();
        packets.flush();
        pool.shutdown();
    }

    // ------------------------------------------------------------------ spawning (driven by VirtualBlockManager)

    /**
     * Acquires one pooled BlockDisplay for a rendered instance. Configuring the
     * returned display (block state, transform) works exactly like the legacy entity.
     */
    public PooledDisplay beginInstance(UUID instanceId, WorldLike world) {
        RenderedInstance instance = instances.computeIfAbsent(instanceId, RenderedInstance::new);
        PooledDisplay display = pool.acquireBlock(world.world());
        instance.displays().add(display);
        return display;
    }

    /** Light-weight adapter so the transport does not depend on Bukkit World directly. */
    public interface WorldLike {
        org.bukkit.World world();
    }

    /**
     * Finalises a spawn: registers the chunk index, marks the instance animated so
     * FULL-tier viewers receive animation deltas, attaches in-range viewers and
     * drains the initial dirty state.
     */
    public void finishInstance(UUID instanceId, Location origin, boolean animated) {
        RenderedInstance instance = instances.get(instanceId);
        if (instance == null) return;
        instance.setAnimated(animated);
        indexChunk(instance, origin);
        for (PooledDisplay d : instance.displays()) {
            packets.markClean(d);
        }
        for (Player p : origin.getWorld().getPlayers()) {
            applyTier(p, instance, origin);
        }
        requestFlush();
    }

    /** Removes an instance: every display is despawned for every viewer and pooled. */
    public void removeInstance(UUID instanceId) {
        RenderedInstance instance = instances.remove(instanceId);
        if (instance == null) return;
        for (PooledDisplay d : instance.displays()) {
            for (UUID viewerId : new ArrayList<>(d.knownClients())) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) packets.despawn(viewer, List.of(d));
            }
        }
        for (PooledDisplay d : instance.displays()) {
            pool.release(d);
        }
        instance.displays().clear();
        instance.viewers().clear();
        removeFromChunkIndex(instance);
        requestFlush();
    }

    // ------------------------------------------------------------------ per-tick update hooks (driven by ModelAnimationManager)

    /**
     * Pushes a new transform for one display of an instance; streamed to FULL-tier
     * viewers on the next {@code flushDeltas}.
     */
    public void updateTransform(UUID instanceId, PooledDisplay display, org.joml.Matrix4fc matrix, int interpolationTicks) {
        RenderedInstance instance = instances.get(instanceId);
        boolean streamed = instance == null || !instance.isAnimated();
        display.setTransform(matrix, interpolationTicks);
        if (streamed) {
            flushDeltas(instanceId);
        }
    }

    /** Ships pending display deltas to FULL-tier viewers of the instance. */
    public void flushDeltas(UUID instanceId) {
        RenderedInstance instance = instances.get(instanceId);
        if (instance == null) return;
        List<Player> receivers = new ArrayList<>(instance.viewers().size());
        for (Map.Entry<UUID, LodTier> e : instance.viewers.entrySet()) {
            if (e.getValue() != LodTier.FULL) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) receivers.add(p);
        }
        for (PooledDisplay d : instance.displays()) {
            packets.broadcastDelta(d, receivers);
        }
    }

    // ------------------------------------------------------------------ viewer management

    /** Player joined: mark them for a full LOD pass (chunk sends arrive progressively). */
    public void handleJoin(Player player) {
        pendingLodDistance.put(player.getUniqueId(), Double.MAX_VALUE);
        pendingFullUpdate.add(player.getUniqueId());
    }

    /** Player quit: drop their client state everywhere. */
    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        pendingLod.remove(id);
        pendingLodDistance.remove(id);
        pendingFullUpdate.remove(id);
        packets.discard(player);
        pool.forgetPlayer(id);
        for (RenderedInstance instance : instances.values()) {
            instance.viewers.remove(id);
        }
    }

    /** Player moved / teleported: recompute LOD on the next tick (throttled). */
    public void markMoved(Player player, boolean teleport) {
        if (teleport) {
            pendingLodDistance.put(player.getUniqueId(), Double.MAX_VALUE);
            pendingFullUpdate.add(player.getUniqueId());
        } else {
            pendingLod.put(player.getUniqueId(), Boolean.TRUE);
        }
    }

    /** Player changed world: every viewer entry of the old world becomes invalid. */
    public void handleWorldChange(Player player) {
        UUID id = player.getUniqueId();
        for (RenderedInstance instance : instances.values()) {
            instance.viewers.remove(id);
        }
        pendingLodDistance.put(id, Double.MAX_VALUE);
        pendingFullUpdate.add(id);
    }

    /** A chunk (re)loaded: re-apply tiers for its instances to nearby players. */
    public void handleChunkLoad(Chunk chunk) {
        Set<RenderedInstance> inChunk = byChunk.get(chunkKey(chunk.getX(), chunk.getZ()));
        if (inChunk == null) return;
        for (RenderedInstance instance : inChunk) {
            for (PooledDisplay d : instance.displays()) {
                if (d.dirty().isAnyDirty()) {
                    d.dirty().clear();
                }
            }
        }
        for (Player p : chunk.getWorld().getPlayers()) {
            pendingFullUpdate.add(p.getUniqueId());
        }
    }

    /** A chunk unloaded: hide its instances from every viewer (the clients lost the chunk). */
    public void handleChunkUnload(Chunk chunk) {
        Set<RenderedInstance> inChunk = byChunk.get(chunkKey(chunk.getX(), chunk.getZ()));
        if (inChunk == null) return;
        for (RenderedInstance instance : inChunk) {
            for (Map.Entry<UUID, LodTier> entry : new HashMap<>(instance.viewers).entrySet()) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null && entry.getValue() != LodTier.HIDDEN) {
                    packets.despawn(viewer, instance.displays());
                }
                instance.viewers.put(entry.getKey(), LodTier.HIDDEN);
            }
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (pendingLod.isEmpty() && pendingFullUpdate.isEmpty() && pendingLodDistance.isEmpty()) {
            // nothing to do this tick - animations flush their own deltas
            return;
        }
        for (UUID playerId : pendingFullUpdate) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                refreshViewers(player, instances.values());
            }
        }
        Set<UUID> movedPlayers = new HashSet<>(pendingLod.keySet());
        for (UUID playerId : movedPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            if (tickCounter % Math.max(1, settings.lodCheckIntervalTicks()) == 0) {
                refreshViewers(player, instances.values());
                pendingLod.remove(playerId);
            }
        }
        pendingFullUpdate.clear();
        pendingLodDistance.clear();
        flush();
    }

    private long tickCounter;

    private void refreshViewers(Player player, Iterable<RenderedInstance> candidates) {
        for (RenderedInstance instance : candidates) {
            PooledDisplay first = instance.displays().isEmpty() ? null : instance.displays().get(0);
            if (first == null) continue;
            Location reference = first.entity().getLocation();
            if (reference.getWorld() != player.getWorld()) continue;
            if (!reference.getWorld().isChunkLoaded(reference.getBlockX() >> 4, reference.getBlockZ() >> 4)) {
                continue;
            }
            applyTier(player, instance, reference);
        }
    }

    private void applyTier(Player player, RenderedInstance instance, Location reference) {
        LodTier tier = LodTier.forDistanceSq(player.getLocation().distanceSquared(reference), settings);
        applyTier(player, instance, tier, reference);
    }

    private void applyTier(Player player, RenderedInstance instance, LodTier tier, Location reference) {
        UUID id = player.getUniqueId();
        LodTier previous = instance.viewers.getOrDefault(id, LodTier.HIDDEN);
        if (previous == tier) return;

        if (tier == LodTier.HIDDEN) {
            if (previous != LodTier.HIDDEN) {
                packets.despawn(player, instance.displays());
            }
            instance.viewers.put(id, LodTier.HIDDEN);
            return;
        }
        boolean needsFullState = previous == LodTier.HIDDEN
                || (previous == LodTier.STATIC && tier == LodTier.FULL && instance.isAnimated());
        if (needsFullState) {
            for (PooledDisplay d : instance.displays()) {
                packets.spawnOrResync(player, d);
            }
        }
        instance.viewers.put(id, tier);
    }

    private void indexChunk(RenderedInstance instance, Location origin) {
        long key = chunkKey(origin.getBlockX() >> 4, origin.getBlockZ() >> 4);
        byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(instance);
    }

    /**
     * Bukkit chunk-key packing (identical to the removed static
     * {@code Chunk.getChunkKey(int, int)} API), inlined so the transport runs
     * on runtimes that dropped the static method.
     */
    private static long chunkKey(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    private void removeFromChunkIndex(RenderedInstance instance) {
        for (Set<RenderedInstance> set : byChunk.values()) {
            set.remove(instance);
        }
    }

    /** Requests one flush before the next tick (spawn / removal paths use direct sends). */
    public void requestFlush() {
        // direct flush: spawn / remove paths happen outside the tick and their packets
        // should not wait a full tick for visibility
        flush();
    }

    private void flush() {
        packets.flush();
    }

    // ------------------------------------------------------------------ metrics

    public record TransportStats(
            int instances, int displays,
            EntityPoolManager.Stats pool,
            PacketOptimizer.Stats packets,
            String nmsVersion) {}

    public TransportStats stats() {
        int displays = 0;
        for (RenderedInstance instance : instances.values()) {
            displays += instance.displays().size();
        }
        return new TransportStats(instances.size(), displays, pool.stats(), packets.stats(), nms.version());
    }
}
