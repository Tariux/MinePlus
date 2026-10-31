package com.mineplus.infrastructure.perf;

/**
 * One closed telemetry tick: the pose slice cost, frames pushed or dropped, and
 * the network traffic produced during that tick. Stored as primitive ring-buffer
 * columns in {@link PerfRegistry}; instances are only created when a reader asks
 * for recent samples, never in a hot path.
 */
public record PerfSample(
        long tick,
        long poseNanos,
        int evaluations,
        int framesApplied,
        int framesDropped,
        int packets,
        int bundles,
        long bundleWeight
) {
}
