package com.mineplus.infrastructure.mob.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A named, ordered sequence of {@link PipelineStage}s a mob runs as one
 * scenario: find a village, march there, raid it. The pipeline is a
 * <b>definition</b> — immutable and shared by every mob assigned to it; all
 * per-mob state lives in the {@link PipelineContext} (its blackboard).
 *
 * <p>Pipelines exist because long-running scenarios are not a single action:
 * they are phases with their own success condition, timeout and hand-off. The
 * engine ticks the current stage on every pass until it completes, then hands
 * the same context to the next one, and finally releases the mob. A pipeline
 * holds an exclusive activity claim while it runs, so no ordinary behavior
 * profile pulls the mob in a different direction halfway through.
 *
 * <p>Typical module use:
 * <pre>{@code
 * MobPipeline raid = MobPipeline.builder("necromancer_village_raid")
 *         .stage(LocateStructureStage.village("find_village", "village"))
 *         .stage(new JourneyStage("march", "village", JourneyRunner.Options.defaults()))
 *         .stage(new RaidStage(...))
 *         .build();
 * api.assignPipeline(this, witch, raid);
 * }</pre>
 *
 * <p>Thread model: definition construction is thread-safe; assignment and
 * execution are main-thread only.
 */
public final class MobPipeline {

    private final String id;
    private final List<PipelineStage> stages;
    private final Consumer<PipelineContext> onComplete;
    private final BiConsumer<PipelineContext, String> onFailure;

    private MobPipeline(String id, List<PipelineStage> stages,
                        Consumer<PipelineContext> onComplete,
                        BiConsumer<PipelineContext, String> onFailure) {
        this.id = id;
        this.stages = List.copyOf(stages);
        this.onComplete = onComplete;
        this.onFailure = onFailure;
    }

    public String id() {
        return id;
    }

    public List<PipelineStage> stages() {
        return stages;
    }

    /** Called after the last stage completes successfully. */
    public Consumer<PipelineContext> onComplete() {
        return onComplete;
    }

    /** Called with the failure reason when a stage fails or overruns. */
    public BiConsumer<PipelineContext, String> onFailure() {
        return onFailure;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** A one-liner stage from a function. */
    public static PipelineStage stage(String id, Function<PipelineContext, StageResult> tick) {
        Objects.requireNonNull(tick, "tick");
        String stageId = requireId(id);
        return new PipelineStage() {
            @Override
            public String id() {
                return stageId;
            }

            @Override
            public StageResult tick(PipelineContext context) {
                StageResult result = tick.apply(context);
                return result == null ? StageResult.RUNNING : result;
            }
        };
    }

    /** A stage that completes after {@code ticks} game ticks of waiting. */
    public static PipelineStage waitTicks(String id, long ticks) {
        long duration = Math.max(1L, ticks);
        return stage(id, context -> context.stageGameTicks() >= duration
                ? StageResult.COMPLETE
                : StageResult.RUNNING);
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("pipeline and stage ids must not be blank");
        }
        return id;
    }

    public static final class Builder {

        private final String id;
        private final List<PipelineStage> stages = new ArrayList<>();
        private final Set<String> stageIds = new HashSet<>();
        private Consumer<PipelineContext> onComplete;
        private BiConsumer<PipelineContext, String> onFailure;

        private Builder(String id) {
            this.id = requireId(id);
        }

        /** Appends a stage; ids must be unique within the pipeline. */
        public Builder stage(PipelineStage stage) {
            Objects.requireNonNull(stage, "stage");
            String stageId = requireId(stage.id());
            if (!stageIds.add(stageId)) {
                throw new IllegalArgumentException("duplicate stage id '" + stageId + "' in pipeline " + id);
            }
            stages.add(stage);
            return this;
        }

        /** Appends a function-shaped stage. */
        public Builder stage(String stageId, Function<PipelineContext, StageResult> tick) {
            return stage(MobPipeline.stage(stageId, tick));
        }

        /** A wait stage, appended in place. */
        public Builder waitTicks(String stageId, long ticks) {
            return stage(MobPipeline.waitTicks(stageId, ticks));
        }

        public Builder onComplete(Consumer<PipelineContext> callback) {
            this.onComplete = callback;
            return this;
        }

        public Builder onFailure(BiConsumer<PipelineContext, String> callback) {
            this.onFailure = callback;
            return this;
        }

        public MobPipeline build() {
            if (stages.isEmpty()) {
                throw new IllegalStateException("pipeline " + id + " has no stages");
            }
            return new MobPipeline(id, stages, onComplete, onFailure);
        }
    }
}
