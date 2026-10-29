package com.mineplus.infrastructure.mob.pipeline;

/**
 * One step of a {@link MobPipeline}. A stage owns a phase of a mobile scenario
 * (find a village, walk there, raid it) and ticks on every engine pass while it
 * is current.
 *
 * <p>State a stage needs to keep between passes goes in the pipeline's
 * blackboard ({@link PipelineContext#put}), not in fields: one pipeline
 * definition is shared by every mob running it, exactly like a
 * {@link com.mineplus.infrastructure.mob.BehaviorAction} definition is shared
 * by every mob of its type.
 *
 * <p>Thread model: main thread only.
 */
public interface PipelineStage {

    /** Stable stage id — used in status output and to look up the current phase. */
    String id();

    /**
     * One pass. Return {@link StageResult#RUNNING} to keep the stage current,
     * {@link StageResult#COMPLETE} to move on, or {@link StageResult#FAILED} to
     * end the pipeline. Throwing is treated as {@code FAILED}.
     */
    StageResult tick(PipelineContext context);

    /** Called once when the stage becomes current. */
    default void onEnter(PipelineContext context) {
    }

    /** Called once when the stage is left, however it ended. */
    default void onExit(PipelineContext context, StageResult result) {
    }

    /**
     * Game ticks this stage may stay current, or {@code <= 0} for no limit.
     * A timed stage fails the pipeline when it overruns, so a scenario that can
     * never reach its condition (a village nobody can path to) does not own the
     * mob forever.
     */
    default long timeoutTicks() {
        return -1L;
    }
}
