package com.mineplus.infrastructure.mob.pipeline;

import java.util.UUID;

/**
 * A live pipeline attached to one mob. Obtained from
 * {@link com.mineplus.infrastructure.mob.MobBehaviorApi#assignPipeline} and
 * useful for status commands and for a module that wants to end its own
 * scenario early.
 */
public interface MobPipelineHandle {

    /** The mob the pipeline drives. */
    UUID entityId();

    /** The pipeline definition's id. */
    String pipelineId();

    /** The stage currently being ticked, or {@code null} after it ended. */
    String stageId();

    /** {@code true} while the pipeline is still attached and advancing. */
    boolean isRunning();

    /** Detaches the pipeline and releases the mob (navigation stops, claim drops). */
    void cancel();
}
