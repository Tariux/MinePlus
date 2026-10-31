package com.mineplus.infrastructure.virtual.animation;

/**
 * How the animation runtime evaluates and ships display poses.
 *
 * <p>Evaluation (sampling clips and composing bone matrices) is pure math over
 * immutable model data, so it can leave the server thread. Applying the result
 * writes to displays and must stay on the main thread; {@code MAX_APPLY_PER_TICK}
 * bounds that slice.</p>
 */
public enum PosePipeline {
    /** Evaluate and apply every pose inside the animation tick (pre-pipeline behaviour). */
    TICK,
    /** Evaluate on the single bounded {@code Mineplus-Pose} thread; apply under the per-tick cap. */
    ASYNC_COMPUTE,
    /** Like {@link #ASYNC_COMPUTE}, and gather a tick's pose writes into one flush pass. */
    ASYNC_SEND;

    public static PosePipeline fromKey(String raw, PosePipeline fallback) {
        if (raw == null) {
            return fallback;
        }
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (PosePipeline pipeline : values()) {
            if (pipeline.name().equals(key)) {
                return pipeline;
            }
        }
        return fallback;
    }

    public boolean asynchronous() {
        return this != TICK;
    }

    /** {@code true} when pose deltas are flushed once after the whole apply slice. */
    public boolean deferredFlush() {
        return this == ASYNC_SEND;
    }
}
