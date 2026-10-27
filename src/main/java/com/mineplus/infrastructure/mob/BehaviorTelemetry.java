package com.mineplus.infrastructure.mob;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free counters surfaced through {@code MobBehaviorApi#telemetrySummary()}
 * so modules (and operators) can see what the engine is doing without a
 * profiler attached. All writes are atomic increments from the main thread;
 * reads are safe anytime.
 */
public final class BehaviorTelemetry {

    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong candidatesSeen = new AtomicLong();
    private final AtomicLong eventsFired = new AtomicLong();
    private final AtomicLong blockEdits = new AtomicLong();
    private final AtomicLong entitySpawns = new AtomicLong();
    private final AtomicLong budgetDeferredRuns = new AtomicLong();
    private final Map<String, AtomicLong> firesByAction = new ConcurrentHashMap<>();

    void runDone() {
        runs.incrementAndGet();
    }

    void candidateSeen() {
        candidatesSeen.incrementAndGet();
    }

    void eventFired(String actionId) {
        eventsFired.incrementAndGet();
        firesByAction.computeIfAbsent(actionId, key -> new AtomicLong()).incrementAndGet();
    }

    void blockEditDone() {
        blockEdits.incrementAndGet();
    }

    void entitySpawnDone() {
        entitySpawns.incrementAndGet();
    }

    void budgetDeferred() {
        budgetDeferredRuns.incrementAndGet();
    }

    public long runs() {
        return runs.get();
    }

    public long candidatesSeen() {
        return candidatesSeen.get();
    }

    public long eventsFired() {
        return eventsFired.get();
    }

    public long blockEdits() {
        return blockEdits.get();
    }

    public long entitySpawns() {
        return entitySpawns.get();
    }

    public long budgetDeferredRuns() {
        return budgetDeferredRuns.get();
    }

    /** {@code actionId -> fire count}, in insertion order for readable status output. */
    public Map<String, Long> firesByAction() {
        Map<String, Long> copy = new LinkedHashMap<>(firesByAction.size());
        firesByAction.forEach((key, value) -> copy.put(key, value.get()));
        return copy;
    }

    /** Multi-line human-readable summary for status commands. */
    public String summary() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("runs=").append(runs())
                .append(", candidates=").append(candidatesSeen())
                .append(", fired=").append(eventsFired())
                .append(", edits=").append(blockEdits())
                .append(", spawns=").append(entitySpawns())
                .append(", deferred=").append(budgetDeferredRuns());
        Map<String, Long> fires = firesByAction();
        if (!fires.isEmpty()) {
            sb.append('\n');
            fires.forEach((action, count) -> sb.append("  ").append(action).append(": ").append(count).append('\n'));
        }
        return sb.toString().stripTrailing();
    }
}
