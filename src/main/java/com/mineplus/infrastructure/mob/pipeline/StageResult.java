package com.mineplus.infrastructure.mob.pipeline;

/** What one pipeline stage wants to happen after the current pass. */
public enum StageResult {

    /** Still working; the stage ticks again on the next engine pass. */
    RUNNING,

    /** The stage is done; the pipeline advances to its next stage. */
    COMPLETE,

    /** The stage cannot continue; the pipeline ends and reports the failure. */
    FAILED
}
