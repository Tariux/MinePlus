package com.mineplus.infrastructure.mob.pipeline;

import com.mineplus.infrastructure.mob.MobBehaviorEngine;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

/**
 * Runs {@link MobPipeline}s attached to individual mobs.
 *
 * <p>The engine ticks every live pipeline once per behavior run, resolving the
 * mob by UUID so a pipeline survives the chunk unloading (it simply resumes
 * when the entity is resolvable again). While a pipeline owns a mob, the
 * ordinary profile evaluator skips that entity entirely — a scenario is a
 * process, not a roll of the odds, and two owners pulling one mob is how a
 * raid ends up halfway between the village and home.
 *
 * <p>Cost is bounded three ways: at most {@code maxPipelinesPerRun} pipelines
 * advance per pass, they share the behavior run's time budget, and each stage
 * is expected to do one small step (the journey runner already paces its own
 * repaths). A pipeline that throws fails itself instead of breaking the run.
 *
 * <p>Thread model: main thread only.
 */
public final class MobPipelineEngine {

    /** Activity-id prefix pipelines claim under; also how the evaluator spots them. */
    public static final String ACTIVITY_PREFIX = "pipeline:";

    private final MobBehaviorEngine engine;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    public MobPipelineEngine(MobBehaviorEngine engine) {
        this.engine = engine;
    }

    /**
     * Attaches {@code pipeline} to {@code entity}, replacing any pipeline the
     * mob already runs.
     *
     * @return a handle, or {@code null} when the entity is not a live mob or
     *         the pipeline/owner is missing
     */
    public MobPipelineHandle assign(Plugin owner, LivingEntity entity, MobPipeline pipeline) {
        if (owner == null || pipeline == null || !(entity instanceof Mob mob)
                || mob.isDead() || !mob.isValid()) {
            return null;
        }
        Running existing = running.get(mob.getUniqueId());
        if (existing != null) {
            if (existing.pipeline == pipeline) {
                return existing;
            }
            existing.cancel();
        }
        Running attached = new Running(owner, mob, pipeline);
        running.put(mob.getUniqueId(), attached);
        attached.claim();
        return attached;
    }

    /** {@code true} while any pipeline drives {@code entity}. */
    public boolean owns(LivingEntity entity) {
        return entity != null && running.containsKey(entity.getUniqueId());
    }

    /** The pipeline handle for {@code entity}, or {@code null}. */
    public MobPipelineHandle handle(LivingEntity entity) {
        return entity == null ? null : running.get(entity.getUniqueId());
    }

    /** Live pipelines; bounded by the entities they drive, reported for diagnostics. */
    public int activeCount() {
        return running.size();
    }

    /**
     * Advances up to {@code maxPerRun} pipelines, stopping early at
     * {@code deadlineNanos} (a {@link System#nanoTime()} value).
     */
    public void tick(long deadlineNanos, int maxPerRun) {
        if (running.isEmpty()) {
            return;
        }
        int cap = Math.max(0, maxPerRun);
        int processed = 0;
        for (Map.Entry<UUID, Running> entry : running.entrySet()) {
            if (processed >= cap || System.nanoTime() >= deadlineNanos) {
                return;
            }
            Running pipeline = entry.getValue();
            Entity resolved = Bukkit.getEntity(entry.getKey());
            if (resolved == null) {
                continue; // unloaded: the pipeline resumes when the mob loads
            }
            processed++;
            if (!(resolved instanceof Mob mob) || mob.isDead() || !mob.isValid()) {
                pipeline.end(false, "entity is gone");
                continue;
            }
            pipeline.advance(mob);
        }
    }

    /** Ends every live pipeline without callbacks (Core shutdown). */
    public void cancelAll() {
        for (Running pipeline : new ArrayList<>(running.values())) {
            pipeline.detach();
        }
    }

    /** One line per live pipeline, for status commands. */
    public List<String> snapshot(int maxLines) {
        List<String> lines = new ArrayList<>();
        List<Running> ordered = new ArrayList<>(running.values());
        ordered.sort(Comparator.comparing(Running::pipelineId));
        for (Running pipeline : ordered) {
            if (lines.size() >= Math.max(1, maxLines)) {
                lines.add("  ... " + (ordered.size() - lines.size()) + " more");
                break;
            }
            lines.add("  " + pipeline.shortId() + " " + pipeline.pipelineId()
                    + " @" + (pipeline.stageId() == null ? "-" : pipeline.stageId()));
        }
        return lines;
    }

    /** One attached pipeline. */
    private final class Running implements MobPipelineHandle {

        private final Plugin owner;
        private final Mob mob;
        private final MobPipeline pipeline;
        private final PipelineContext context;
        private final String claimId;
        private int stageIndex;
        private int enteredStage = -1;
        private volatile boolean alive = true;

        Running(Plugin owner, Mob mob, MobPipeline pipeline) {
            this.owner = owner;
            this.mob = mob;
            this.pipeline = pipeline;
            this.context = new PipelineContext(engine, owner, mob, pipeline.id());
            this.claimId = ACTIVITY_PREFIX + pipeline.id();
        }

        void claim() {
            context.claim(claimId, null, 0.0, 200L);
        }

        void advance(Mob liveMob) {
            if (!alive) {
                return;
            }
            List<PipelineStage> stages = pipeline.stages();
            if (stageIndex >= stages.size()) {
                end(true, null);
                return;
            }
            // A higher-priority scenario may take the mob over; the pipeline
            // must stop instead of fighting it for the same legs.
            if (!context.ownsClaim(claimId)) {
                claim();
                if (!context.ownsClaim(claimId)) {
                    end(false, "taken over by another activity");
                    return;
                }
            } else {
                claim(); // renew before the stage can take a while
            }
            PipelineStage stage = stages.get(stageIndex);
            if (enteredStage != stageIndex) {
                context.beginStage(stage.id(), stageIndex);
                enteredStage = stageIndex;
                try {
                    stage.onEnter(context);
                } catch (Throwable failure) {
                    end(false, "stage '" + stage.id() + "' onEnter threw: " + failure);
                    return;
                }
            }
            long timeout = stage.timeoutTicks();
            if (timeout > 0 && context.stageGameTicks() > timeout) {
                leave(stage, StageResult.FAILED);
                end(false, "stage '" + stage.id() + "' timed out after " + timeout + " ticks");
                return;
            }
            StageResult result;
            try {
                result = stage.tick(context);
            } catch (Throwable failure) {
                leave(stage, StageResult.FAILED);
                end(false, "stage '" + stage.id() + "' threw: " + failure);
                return;
            }
            if (context.failureReason() != null) {
                leave(stage, StageResult.FAILED);
                end(false, context.failureReason());
                return;
            }
            if (result == null || result == StageResult.RUNNING) {
                return;
            }
            leave(stage, result);
            if (result == StageResult.FAILED) {
                end(false, "stage '" + stage.id() + "' reported failure");
                return;
            }
            stageIndex++;
            enteredStage = -1;
            context.resetFailure();
            if (stageIndex >= stages.size()) {
                end(true, null);
            }
        }

        private void leave(PipelineStage stage, StageResult result) {
            try {
                stage.onExit(context, result);
            } catch (Throwable ignored) {
                // An exit hook must not mask the stage's own outcome.
            }
        }

        /** Completes or fails: releases the mob, notifies the module, detaches. */
        void end(boolean success, String reason) {
            if (!alive) {
                return;
            }
            alive = false;
            try {
                if (success) {
                    if (pipeline.onComplete() != null) {
                        pipeline.onComplete().accept(context);
                    }
                } else if (pipeline.onFailure() != null) {
                    pipeline.onFailure().accept(context, reason == null ? "failed" : reason);
                }
            } catch (Throwable logged) {
                owner.getLogger().warning("[mob-pipeline] " + pipeline.id()
                        + " completion callback threw: " + logged);
            }
            context.releaseClaim(claimId);
            if (mob.isValid() && !mob.isDead()) {
                MobNavigator.stop(mob);
            }
            engine.journeys().abandon(mob);
            running.remove(mob.getUniqueId(), this);
        }

        /** Silent removal (Core shutdown). */
        void detach() {
            if (!alive) {
                return;
            }
            alive = false;
            context.releaseClaim(claimId);
            engine.journeys().abandon(mob);
            running.remove(mob.getUniqueId(), this);
        }

        String shortId() {
            return mob.getUniqueId().toString().substring(0, 8);
        }

        @Override
        public UUID entityId() {
            return mob.getUniqueId();
        }

        @Override
        public String pipelineId() {
            return pipeline.id();
        }

        @Override
        public String stageId() {
            return alive ? context.stageId() : null;
        }

        @Override
        public boolean isRunning() {
            return alive;
        }

        @Override
        public void cancel() {
            end(false, "cancelled");
        }
    }
}
