package com.mineplus.infrastructure.mob.pipeline;

import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.pipeline.stage.JourneyStage;
import com.mineplus.infrastructure.mob.pipeline.stage.LocateStructureStage;
import com.mineplus.infrastructure.mob.pipeline.stage.SettleStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The settler recipe as a pipeline: <b>locate → travel → settle → build</b>.
 *
 * <p>Every "mob that moves somewhere and lives there" scenario needs the same
 * four phases, and writing them by hand is how they drift: a locator that
 * forgets to re-anchor its target, a march with no stall recovery, a build that
 * starts before the mob has arrived. This builder assembles the Core's stages
 * (cached structure lookup, journey legs with arrival-side bell anchoring,
 * home-spot selection) and leaves only the build step to the module:
 *
 * <pre>{@code
 * MobPipeline settle = SettlerPipeline.builder("pilgrim_settle")
 *         .locateVillage()
 *         .march(JourneyRunner.Options.defaults())
 *         .settle(6.0, 40L)
 *         .onSettled(context -> buildCamp(context))
 *         .build();
 * }</pre>
 *
 * <p>A stage result is a real state transition, not a poll loop: the pipeline
 * engine ticks only the current stage, so a settler is in exactly one of
 * LOCATE, TRAVEL, SETTLE or BUILD at any moment, and the blackboard is the
 * state it carries between them.
 */
public final class SettlerPipeline {

    private SettlerPipeline() {
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {

        private final String id;
        private String structureKey;
        private int searchRadiusChunks = 96;
        private long locateTimeoutTicks = 2_400L;
        private String destinationKey = "settlement";
        private JourneyRunner.Options marchOptions = JourneyRunner.Options.defaults();
        private double settleRadius = 6.0;
        private long settleTicks = 40L;
        private Consumer<PipelineContext> onSettled;
        private Consumer<PipelineContext> onComplete;
        private BiConsumer<PipelineContext, String> onFailure;

        private Builder(String id) {
            this.id = id;
        }

        /** Locates the nearest village and remembers it as the settlement. */
        public Builder locateVillage() {
            return locate("village", 96, 2_400L);
        }

        /** Locates the nearest structure of {@code structureKey}. */
        public Builder locate(String structureKey, int searchRadiusChunks, long timeoutTicks) {
            this.structureKey = structureKey;
            this.searchRadiusChunks = searchRadiusChunks;
            this.locateTimeoutTicks = timeoutTicks;
            return this;
        }

        /**
         * Uses a destination the module already put on the blackboard instead
         * of locating one (a village a crew agreed on).
         */
        public Builder destinationKey(String destinationKey) {
            this.destinationKey = destinationKey;
            return this;
        }

        /** How the march walks: stride, patience, arrival distance. */
        public Builder march(JourneyRunner.Options options) {
            this.marchOptions = options;
            return this;
        }

        /**
         * How the mob takes its place on arrival: a standable home spot within
         * {@code radius} blocks of the anchor, then {@code ticks} of settling
         * before the build callback runs.
         */
        public Builder settle(double radius, long ticks) {
            this.settleRadius = radius;
            this.settleTicks = ticks;
            return this;
        }

        /** The module's build step; runs once, after settling. */
        public Builder onSettled(Consumer<PipelineContext> onSettled) {
            this.onSettled = onSettled;
            return this;
        }

        public Builder onComplete(Consumer<PipelineContext> onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        public Builder onFailure(BiConsumer<PipelineContext, String> onFailure) {
            this.onFailure = onFailure;
            return this;
        }

        public MobPipeline build() {
            MobPipeline.Builder pipeline = MobPipeline.builder(id);
            if (structureKey != null) {
                pipeline.stage(new LocateStructureStage("locate", structureKey, destinationKey,
                        searchRadiusChunks, locateTimeoutTicks));
            }
            // The stage re-anchors to the structure's bell once the mob is near:
            // the locator saw the feature point from far away, when the village's
            // chunks (and its bell) were not loaded.
            pipeline.stage(new JourneyStage("travel", destinationKey, structureKey, marchOptions));
            pipeline.stage(new SettleStage("settle", destinationKey, settleRadius, settleTicks, onSettled));
            if (onComplete != null) {
                pipeline.onComplete(onComplete);
            }
            if (onFailure != null) {
                pipeline.onFailure(onFailure);
            }
            return pipeline.build();
        }
    }
}
