package com.mineplus.infrastructure.world;

import com.mineplus.infrastructure.perf.PerfRegistry;
import com.mineplus.util.DebugLogger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Defers bulk world edits across ticks under a fixed budget instead of spiking
 * one tick (craters, machine teardown, structure damage).
 *
 * <p>The queue is main-thread only: callers enqueue detached
 * {@link BlockEdit}s (position + {@code BlockData} snapshot), and one drain pass
 * per tick applies at most {@code WORLD_EDITS.MAX_BLOCKS_PER_TICK} blocks or
 * {@code MAX_MS_PER_TICK} milliseconds, whichever comes first. {@code HIGH}
 * edits drain strictly before {@code LOW}; within a bucket order is FIFO per
 * world. Edits in unloaded chunks are skipped and counted — the queue never
 * forces a chunk load.</p>
 *
 * <p>With {@code WORLD_EDITS.ENABLED: false} (the default) no task exists and
 * {@link #enqueue} applies each edit immediately on the calling (main) thread,
 * preserving the historical behavior. An off-thread enqueue in that mode is
 * counted as dropped and reported once: Bukkit world access is main-thread
 * only, and the queue never bypasses that rule.</p>
 */
public final class DeferredEditQueue {

    /** Hard ceiling of blocks a shutdown flush may apply before dropping the rest. */
    private static final int SHUTDOWN_BLOCK_CAP = 65_536;

    /**
     * @param applied   edits written to the world
     * @param skipped   edits omitted because their chunk was not loaded
     * @param dropped   edits refused (off-thread immediate apply, cancelled worlds, shutdown cap)
     * @param pending   edits currently waiting in the queue
     * @param worlds    worlds with a live queue
     */
    public record Stats(long applied, long skipped, long dropped, int pending, int worlds) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);
    }

    private final WorldEditSettings settings;
    private final Map<UUID, WorldQueue> queues = new ConcurrentHashMap<>();
    private final LongAdder applied = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean offThreadReported = new AtomicBoolean();

    private volatile PerfRegistry perf;
    private BukkitTask tickTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;

    public DeferredEditQueue(WorldEditSettings settings) {
        this.settings = settings == null ? WorldEditSettings.defaults() : settings;
    }

    public WorldEditSettings settings() {
        return settings;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** Telemetry sink for the pending-depth gauge (may be {@code null}). */
    public void setPerfRegistry(PerfRegistry perf) {
        this.perf = perf;
    }

    /**
     * Starts the one-tick drain task when enabled. Mirrors the runtime's Folia
     * branch: the global region scheduler when present, the classic timer
     * otherwise.
     */
    public void start(JavaPlugin plugin) {
        if (!settings.enabled() || plugin == null || tickTask != null || foliaTask != null) {
            return;
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> drain(), 1L, 1L);
        } catch (ClassNotFoundException e) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        }
    }

    /** Cancels the drain task and flushes under a hard cap; remaining edits are dropped and logged. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
        if (!settings.enabled()) {
            return;
        }
        drainAllInternal(SHUTDOWN_BLOCK_CAP);
        int remaining = pending();
        if (remaining > 0) {
            int lost = dropRemaining();
            DebugLogger.warning("[WorldEdits] Shutdown flush cap reached; dropped " + lost
                    + " queued block edit(s).");
        }
    }

    /**
     * Queues edits for the next drain pass, or applies them immediately when
     * the queue is disabled.
     *
     * @return the number of edits accepted (queued, or applied/skipped immediately)
     */
    public int enqueue(World world, List<BlockEdit> edits) {
        if (world == null || edits == null || edits.isEmpty()) {
            return 0;
        }
        if (!settings.enabled()) {
            return applyImmediately(world, edits);
        }
        WorldQueue queue = queues.computeIfAbsent(world.getUID(), id -> new WorldQueue(world));
        int accepted = 0;
        for (BlockEdit edit : edits) {
            if (edit == null) {
                continue;
            }
            if (edit.priority() == BlockEdit.Priority.HIGH) {
                queue.high.add(edit);
            } else {
                queue.low.add(edit);
            }
            accepted++;
        }
        if (accepted > 0) {
            pending.addAndGet(accepted);
        }
        return accepted;
    }

    /** Queues one batch ({@link EditBatch} already coalesced duplicate cells). */
    public int enqueue(World world, EditBatch batch) {
        return batch == null ? 0 : enqueue(world, batch.edits());
    }

    /** Edits waiting to be applied. */
    public int pending() {
        return Math.max(0, pending.get());
    }

    public Stats stats() {
        return new Stats(applied.sum(), skipped.sum(), dropped.sum(), pending(), queues.size());
    }

    /**
     * Drains everything queued right now (main thread). Returns the applied
     * count; skipped edits are counted and discarded.
     */
    public int flushNow() {
        return drainAll(Integer.MAX_VALUE);
    }

    /** Drops a world's queued edits; returns the number discarded. */
    public int cancel(World world) {
        if (world == null) {
            return 0;
        }
        WorldQueue removed = queues.remove(world.getUID());
        if (removed == null) {
            return 0;
        }
        int lost = removed.high.size() + removed.low.size();
        removed.high.clear();
        removed.low.clear();
        if (lost > 0) {
            pending.addAndGet(-lost);
            dropped.add(lost);
        }
        return lost;
    }

    // ------------------------------------------------------------------ draining

    private void drain() {
        if (!settings.enabled()) {
            return;
        }
        int remaining = settings.maxBlocksPerTick();
        long deadline = settings.maxMsPerTick() <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + settings.maxMsPerTick() * 1_000_000L;
        for (WorldQueue queue : queues.values()) {
            if (remaining <= 0) {
                break;
            }
            remaining -= drainQueue(queue, deadline, remaining);
        }
        PerfRegistry registry = perf;
        if (registry != null) {
            registry.setGauge(PerfRegistry.Gauge.DEFERRED_EDIT_QUEUE_DEPTH, pending());
        }
    }

    /** Applies up to {@code cap} edits across every world with no time bound. */
    private int drainAll(int cap) {
        if (!Bukkit.isPrimaryThread()) {
            reportOffThread();
            return 0;
        }
        return drainAllInternal(cap);
    }

    /**
     * The drain body without the main-thread guard: used by the plugin
     * shutdown path, which already runs on the lifecycle thread.
     */
    private int drainAllInternal(int cap) {
        int remaining = cap;
        int total = 0;
        for (WorldQueue queue : queues.values()) {
            if (remaining <= 0) {
                break;
            }
            int done = drainQueue(queue, Long.MAX_VALUE, remaining);
            remaining -= done;
            total += done;
        }
        return total;
    }

    /** High bucket first, then low; returns applied count. */
    private int drainQueue(WorldQueue queue, long deadline, int budget) {
        int appliedCount = applyBucket(queue, queue.high, deadline, budget);
        if (appliedCount >= budget) {
            return appliedCount;
        }
        return appliedCount + applyBucket(queue, queue.low, deadline, budget - appliedCount);
    }

    private int applyBucket(WorldQueue queue, ConcurrentLinkedQueue<BlockEdit> bucket,
                            long deadline, int budget) {
        int appliedCount = 0;
        while (appliedCount < budget) {
            if (System.nanoTime() > deadline) {
                break;
            }
            BlockEdit edit = bucket.poll();
            if (edit == null) {
                break;
            }
            pending.decrementAndGet();
            World world = queue.world;
            if (!world.isChunkLoaded(edit.x() >> 4, edit.z() >> 4)) {
                skipped.increment();
                continue;
            }
            try {
                Block block = world.getBlockAt(edit.x(), edit.y(), edit.z());
                block.setBlockData(edit.data(), edit.physics());
                applied.increment();
                appliedCount++;
            } catch (RuntimeException failure) {
                dropped.increment();
                DebugLogger.warning("[WorldEdits] Could not apply edit at "
                        + edit.x() + "," + edit.y() + "," + edit.z() + ": " + failure.getMessage());
            }
        }
        return appliedCount;
    }

    /** Drops everything left; returns the discarded count. */
    private int dropRemaining() {
        int lost = 0;
        for (WorldQueue queue : queues.values()) {
            lost += queue.high.size() + queue.low.size();
            queue.high.clear();
            queue.low.clear();
        }
        int waiting = pending.getAndSet(0);
        int count = Math.max(lost, waiting);
        if (count > 0) {
            dropped.add(count);
        }
        queues.clear();
        return count;
    }

    /** Disabled mode: immediate apply on the caller's (main) thread. */
    private int applyImmediately(World world, List<BlockEdit> edits) {
        if (!Bukkit.isPrimaryThread()) {
            dropped.add(edits.size());
            reportOffThread();
            return 0;
        }
        int accepted = 0;
        for (BlockEdit edit : edits) {
            if (edit == null) {
                continue;
            }
            if (!world.isChunkLoaded(edit.x() >> 4, edit.z() >> 4)) {
                skipped.increment();
                accepted++;
                continue;
            }
            try {
                world.getBlockAt(edit.x(), edit.y(), edit.z()).setBlockData(edit.data(), edit.physics());
                applied.increment();
                accepted++;
            } catch (RuntimeException failure) {
                dropped.increment();
                DebugLogger.warning("[WorldEdits] Could not apply edit at "
                        + edit.x() + "," + edit.y() + "," + edit.z() + ": " + failure.getMessage());
            }
        }
        return accepted;
    }

    private void reportOffThread() {
        if (offThreadReported.compareAndSet(false, true)) {
            DebugLogger.warning("[WorldEdits] World edits were submitted from a non-main thread;"
                    + " they were refused. Bukkit world access is main-thread only.");
        }
    }

    private static final class WorldQueue {

        private final World world;
        private final ConcurrentLinkedQueue<BlockEdit> high = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<BlockEdit> low = new ConcurrentLinkedQueue<>();

        private WorldQueue(World world) {
            this.world = world;
        }
    }
}
