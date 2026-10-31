package com.mineplus.infrastructure.perf;

import com.google.gson.GsonBuilder;

import java.util.Map;

/**
 * Immutable point-in-time view of {@link PerfRegistry}: lifetime counters,
 * current gauges, ring-buffer averages and the adaptive valve state. Produced on
 * demand by {@code /mineplus perf} and {@code /mineplus perf dump}.
 */
public record PerfSnapshot(
        long capturedAtMillis,
        boolean telemetry,
        boolean adaptive,
        int tickIntervalMultiplier,
        int poseBudgetMs,
        Map<String, Long> counters,
        Map<String, Long> gauges,
        Map<String, Double> averages
) {

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }
}
