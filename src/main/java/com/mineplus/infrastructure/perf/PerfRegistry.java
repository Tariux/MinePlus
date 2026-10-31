package com.mineplus.infrastructure.perf;

import com.mineplus.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The Core's performance registry: one instance owned by
 * {@link com.mineplus.infrastructure.PluginContext}, never static state.
 *
 * <p>Hot paths pay one volatile read (the telemetry branch) and a
 * {@link LongAdder#add(long)} per observation; when telemetry is disabled every
 * recorder returns on a final boolean. A one-tick scheduled close appends the
 * accumulated row to fixed-size primitive rings, so readers
 * ({@code /mineplus perf}) never touch live state and writers never allocate.</p>
 *
 * <p>The adaptive valve lives here too: it watches the pose slice cost reported
 * by the animation runtime and, after {@value #DEGRADE_OVER_BUDGET_TICKS}
 * consecutive ticks over {@code PERFORMANCE.POSE_BUDGET_MS}, raises the
 * effective animation tick interval and halves the bundle weight limit. It
 * restores stepwise after {@value #RESTORE_UNDER_BUDGET_TICKS} ticks under
 * budget. It never changes gameplay, damage, hit registration or persistence.</p>
 */
public final class PerfRegistry {

    /** Lifetime counters (monotonic unless {@link #reset()} is called). */
    public enum Counter {
        POSE_EVALUATIONS,
        FRAMES_APPLIED,
        FRAMES_DROPPED,
        PACKETS_SENT,
        BUNDLES_SENT,
        TEXEL_BAKES_QUEUED,
        TEXEL_BAKES_COMPLETED,
        TEXEL_BAKES_FAILED,
        PACK_COMPILES,
        MOB_ENGINE_RUNS,
        ADAPTIVE_CHANGES
    }

    /** Last-known instant values (gauges). */
    public enum Gauge {
        ACTIVE_DISPLAYS,
        IDLE_POOLED,
        VIEWERS_FULL,
        VIEWERS_STATIC,
        VIEWERS_HIDDEN,
        ANIMATIONS_ACTIVE,
        PACK_COMPILE_MILLIS,
        MOB_ENGINE_RUN_MILLIS,
        DEFERRED_EDIT_QUEUE_DEPTH,
        ACTIVE_FX_UNITS,
        POSE_DEGRADED
    }

    private static final int RING_SIZE = 1200;
    private static final int DEGRADE_OVER_BUDGET_TICKS = 20;
    private static final int RESTORE_UNDER_BUDGET_TICKS = 200;

    private final PerfSettings settings;
    private final boolean telemetry;
    private final boolean adaptive;
    private final long poseBudgetNanos;

    private final LongAdder[] counters = new LongAdder[Counter.values().length];
    private final AtomicLongArray gauges = new AtomicLongArray(Gauge.values().length);

    private final LongAdder rowPoseNanos = new LongAdder();
    private final LongAdder rowEvaluations = new LongAdder();
    private final LongAdder rowApplied = new LongAdder();
    private final LongAdder rowDropped = new LongAdder();
    private final LongAdder rowPackets = new LongAdder();
    private final LongAdder rowBundles = new LongAdder();
    private final LongAdder rowWeight = new LongAdder();

    private final long[] ringTick = new long[RING_SIZE];
    private final long[] ringPoseNanos = new long[RING_SIZE];
    private final int[] ringEvaluations = new int[RING_SIZE];
    private final int[] ringApplied = new int[RING_SIZE];
    private final int[] ringDropped = new int[RING_SIZE];
    private final int[] ringPackets = new int[RING_SIZE];
    private final int[] ringBundles = new int[RING_SIZE];
    private final long[] ringWeight = new long[RING_SIZE];
    private int ringIndex;
    private long ringCount;

    private volatile int tickIntervalMultiplier = 1;
    private int overBudgetTicks;
    private int underBudgetTicks;

    private BukkitTask tickTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;

    public PerfRegistry(PerfSettings settings) {
        this.settings = settings == null ? PerfSettings.defaults() : settings;
        this.telemetry = this.settings.telemetry();
        this.adaptive = this.settings.adaptive();
        this.poseBudgetNanos = this.settings.poseBudgetNanos();
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
        }
    }

    /** Starts the once-per-tick row close. No task is scheduled when telemetry is off. */
    public void start(JavaPlugin plugin) {
        if (!telemetry || plugin == null || tickTask != null || foliaTask != null) {
            return;
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> closeTick(), 1L, 1L);
        } catch (ClassNotFoundException e) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::closeTick, 1L, 1L);
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
    }

    public PerfSettings settings() {
        return settings;
    }

    public boolean telemetryEnabled() {
        return telemetry;
    }

    public boolean adaptiveEnabled() {
        return adaptive;
    }

    // ------------------------------------------------------------------ recording

    public void add(Counter counter, long delta) {
        if (!telemetry || delta == 0) {
            return;
        }
        counters[counter.ordinal()].add(delta);
    }

    public void setGauge(Gauge gauge, long value) {
        if (!telemetry) {
            return;
        }
        gauges.set(gauge.ordinal(), value);
    }

    public void addGauge(Gauge gauge, long delta) {
        if (!telemetry || delta == 0) {
            return;
        }
        gauges.addAndGet(gauge.ordinal(), delta);
    }

    /**
     * Records one animation pose pass: the wall-clock cost of evaluating and
     * applying the whole tick's frames, how many instances were evaluated, how
     * many bindings were pushed and how many frames were dropped by the apply
     * cap. Also feeds the adaptive valve.
     */
    public void recordPoseSlice(long nanos, int evaluations, int applied, int dropped) {
        if (nanos < 0) {
            nanos = 0;
        }
        if (telemetry) {
            rowPoseNanos.add(nanos);
            rowEvaluations.add(evaluations);
            rowApplied.add(applied);
            rowDropped.add(dropped);
            counters[Counter.POSE_EVALUATIONS.ordinal()].add(evaluations);
            counters[Counter.FRAMES_APPLIED.ordinal()].add(applied);
            counters[Counter.FRAMES_DROPPED.ordinal()].add(dropped);
        }
        if (adaptive) {
            observePoseBudget(nanos);
        }
    }

    /** Records packets leaving the send path (used for the per-tick network rows). */
    public void recordNetwork(int packets, int bundles, long weight) {
        if (!telemetry) {
            return;
        }
        rowPackets.add(packets);
        rowBundles.add(bundles);
        rowWeight.add(weight);
        counters[Counter.PACKETS_SENT.ordinal()].add(packets);
        counters[Counter.BUNDLES_SENT.ordinal()].add(bundles);
    }

    // ------------------------------------------------------------------ adaptive valve

    private void observePoseBudget(long nanos) {
        if (nanos > poseBudgetNanos) {
            overBudgetTicks++;
            underBudgetTicks = 0;
        } else {
            underBudgetTicks++;
            overBudgetTicks = 0;
        }
        if (overBudgetTicks >= DEGRADE_OVER_BUDGET_TICKS) {
            overBudgetTicks = 0;
            applyMultiplier(Math.min(4, tickIntervalMultiplier * 2));
        } else if (underBudgetTicks >= RESTORE_UNDER_BUDGET_TICKS) {
            underBudgetTicks = 0;
            applyMultiplier(Math.max(1, tickIntervalMultiplier / 2));
        }
    }

    private void applyMultiplier(int next) {
        int previous = tickIntervalMultiplier;
        if (next == previous) {
            return;
        }
        tickIntervalMultiplier = next;
        counters[Counter.ADAPTIVE_CHANGES.ordinal()].increment();
        DebugLogger.warning("[Performance] Adaptive valve: pose slice over budget for "
                + DEGRADE_OVER_BUDGET_TICKS + " ticks; animation interval x" + previous
                + " -> x" + next + " (bundle weight limit scaled 1/" + next + ").");
    }

    /** Effective animation tick interval under the valve, clamped to 4 ticks. */
    public int effectiveTickInterval(int base) {
        int safeBase = Math.max(1, base);
        if (!adaptive) {
            return safeBase;
        }
        return Math.min(4, safeBase * tickIntervalMultiplier);
    }

    /** Effective per-bundle packet-weight ceiling under the valve. */
    public int effectiveBundleWeightLimit(int configured) {
        int safeConfigured = Math.max(1, configured);
        if (!adaptive) {
            return safeConfigured;
        }
        return Math.max(256, safeConfigured / tickIntervalMultiplier);
    }

    public int tickIntervalMultiplier() {
        return tickIntervalMultiplier;
    }

    // ------------------------------------------------------------------ tick rows & snapshots

    private void closeTick() {
        if (!telemetry) {
            return;
        }
        long tick = ringCount++;
        int i = ringIndex;
        ringIndex = (i + 1) % RING_SIZE;
        ringTick[i] = tick;
        ringPoseNanos[i] = rowPoseNanos.sumThenReset();
        ringEvaluations[i] = (int) rowEvaluations.sumThenReset();
        ringApplied[i] = (int) rowApplied.sumThenReset();
        ringDropped[i] = (int) rowDropped.sumThenReset();
        ringPackets[i] = (int) rowPackets.sumThenReset();
        ringBundles[i] = (int) rowBundles.sumThenReset();
        ringWeight[i] = rowWeight.sumThenReset();
    }

    /** Most recent closed tick rows, oldest first, capped at the ring size. */
    public java.util.List<PerfSample> recentSamples(int limit) {
        int available = (int) Math.min(ringCount, RING_SIZE);
        int wanted = Math.min(Math.max(0, limit), available);
        java.util.List<PerfSample> samples = new java.util.ArrayList<>(wanted);
        int start = (ringIndex - wanted + RING_SIZE) % RING_SIZE;
        for (int n = 0; n < wanted; n++) {
            int i = (start + n) % RING_SIZE;
            samples.add(new PerfSample(ringTick[i], ringPoseNanos[i], ringEvaluations[i],
                    ringApplied[i], ringDropped[i], ringPackets[i], ringBundles[i], ringWeight[i]));
        }
        return samples;
    }

    public PerfSnapshot snapshot() {
        Map<String, Long> counterMap = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) {
            counterMap.put(counter.name(), counters[counter.ordinal()].sum());
        }
        Map<String, Long> gaugeMap = new LinkedHashMap<>();
        for (Gauge gauge : Gauge.values()) {
            gaugeMap.put(gauge.name(), gauges.get(gauge.ordinal()));
        }
        Map<String, Double> averages = new LinkedHashMap<>();
        int available = (int) Math.min(ringCount, RING_SIZE);
        if (available > 0) {
            long poseSum = 0;
            long poseMax = 0;
            long packetSum = 0;
            long bundleSum = 0;
            long weightSum = 0;
            for (int n = 0; n < available; n++) {
                int i = (ringIndex - 1 - n + 2 * RING_SIZE) % RING_SIZE;
                poseSum += ringPoseNanos[i];
                poseMax = Math.max(poseMax, ringPoseNanos[i]);
                packetSum += ringPackets[i];
                bundleSum += ringBundles[i];
                weightSum += ringWeight[i];
            }
            averages.put("poseMillisAvg", poseSum / 1_000_000.0 / available);
            averages.put("poseMillisMax", poseMax / 1_000_000.0);
            averages.put("packetsPerTickAvg", (double) packetSum / available);
            averages.put("bundlesPerTickAvg", (double) bundleSum / available);
            averages.put("bundleWeightPerTickAvg", (double) weightSum / available);
            averages.put("samples", (double) available);
        }
        return new PerfSnapshot(
                System.currentTimeMillis(),
                telemetry,
                adaptive,
                tickIntervalMultiplier,
                settings.poseBudgetMs(),
                counterMap,
                gaugeMap,
                averages);
    }

    public long counter(Counter counter) {
        return counters[counter.ordinal()].sum();
    }

    public long gauge(Gauge gauge) {
        return gauges.get(gauge.ordinal());
    }

    /** Zeroes counters, ring history and the adaptive valve. Gauges keep their live values. */
    public void reset() {
        for (LongAdder counter : counters) {
            counter.reset();
        }
        rowPoseNanos.reset();
        rowEvaluations.reset();
        rowApplied.reset();
        rowDropped.reset();
        rowPackets.reset();
        rowBundles.reset();
        rowWeight.reset();
        java.util.Arrays.fill(ringTick, 0L);
        java.util.Arrays.fill(ringPoseNanos, 0L);
        java.util.Arrays.fill(ringEvaluations, 0);
        java.util.Arrays.fill(ringApplied, 0);
        java.util.Arrays.fill(ringDropped, 0);
        java.util.Arrays.fill(ringPackets, 0);
        java.util.Arrays.fill(ringBundles, 0);
        java.util.Arrays.fill(ringWeight, 0L);
        ringIndex = 0;
        ringCount = 0;
        overBudgetTicks = 0;
        underBudgetTicks = 0;
        tickIntervalMultiplier = 1;
    }
}
