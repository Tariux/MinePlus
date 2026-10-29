package com.mineplus.infrastructure.mob.pipeline;

import com.mineplus.infrastructure.mob.MobBehaviorEngine;
import com.mineplus.infrastructure.mob.MobEditSession;
import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.JourneyStatus;
import com.mineplus.infrastructure.mob.journey.StructureLookup;
import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

/**
 * The per-mob state a {@link MobPipeline} stage works against: the mob, its
 * plugin, the engine services, and a blackboard that carries values from one
 * stage to the next (the village a locator found, the target a raid picked).
 *
 * <p>One context exists per running pipeline and is reused across stages and
 * passes, so stages stay allocation-free; a stage that needs per-pass scratch
 * state should use a local variable, and anything that must survive to the
 * next pass belongs in the blackboard.
 *
 * <p>Thread model: main thread only.
 */
public final class PipelineContext {

    private final MobBehaviorEngine engine;
    private final Plugin owner;
    private final Mob mob;
    private final String pipelineId;
    private final Map<String, Object> blackboard = new HashMap<>();

    private String stageId;
    private int stageIndex;
    private long startedGameTick;
    private long stageStartedGameTick;
    private String failureReason;

    PipelineContext(MobBehaviorEngine engine, Plugin owner, Mob mob, String pipelineId) {
        this.engine = engine;
        this.owner = owner;
        this.mob = mob;
        this.pipelineId = pipelineId;
        this.startedGameTick = now();
        this.stageStartedGameTick = startedGameTick;
    }

    /** The mob this pipeline drives. */
    public Mob mob() {
        return mob;
    }

    /** The mob's world. */
    public World world() {
        return mob.getWorld();
    }

    /** The module that assigned the pipeline. */
    public Plugin plugin() {
        return owner;
    }

    /** The pipeline definition's id. */
    public String pipelineId() {
        return pipelineId;
    }

    /** The id of the stage currently running. */
    public String stageId() {
        return stageId;
    }

    /** Zero-based index of the current stage. */
    public int stageIndex() {
        return stageIndex;
    }

    /** The current world game time. */
    public long gameTime() {
        return now();
    }

    /** Game ticks the pipeline has been running. */
    public long pipelineGameTicks() {
        return Math.max(0L, now() - startedGameTick);
    }

    /** Game ticks the current stage has been running. */
    public long stageGameTicks() {
        return Math.max(0L, now() - stageStartedGameTick);
    }

    /** The mob's follow range (the practical limit of one vanilla path). */
    public double followRange() {
        return MobAttributes.followRange(mob);
    }

    /** The distance one path search may span for this mob. */
    public double pathRange() {
        return MobAttributes.pathRange(mob);
    }

    // ------------------------------------------------------------------
    // Blackboard
    // ------------------------------------------------------------------

    /** Stores a value for later stages; a {@code null} value removes the key. */
    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            blackboard.remove(key);
        } else {
            blackboard.put(key, value);
        }
    }

    /** The stored value, or {@code null}. */
    public Object get(String key) {
        return key == null ? null : blackboard.get(key);
    }

    /** The stored value when it is a {@link Location}, otherwise {@code null}. */
    public Location location(String key) {
        return blackboard.get(key) instanceof Location location ? location.clone() : null;
    }

    public boolean has(String key) {
        return key != null && blackboard.containsKey(key);
    }

    public void remove(String key) {
        if (key != null) {
            blackboard.remove(key);
        }
    }

    /** The live blackboard; stage authors should prefer the typed accessors. */
    public Map<String, Object> blackboard() {
        return blackboard;
    }

    // ------------------------------------------------------------------
    // Core services
    // ------------------------------------------------------------------

    /**
     * The nearest structure of a type, through the engine's cache and
     * {@link com.mineplus.infrastructure.mob.journey.StructureAnchor} (a
     * village's bell, not its roof).
     */
    public Location nearestStructure(String structureKey, int maxRadiusChunks) {
        StructureLookup lookup = engine.structures();
        return lookup == null ? null : lookup.nearest(world(), structureKey, mob.getLocation(), maxRadiusChunks);
    }

    /**
     * Walks one leg toward {@code destination} through the shared journey
     * runner: follow-range-sized legs, standable waypoints, stall recovery.
     */
    public JourneyStatus advanceJourney(Location destination, JourneyRunner.Options options) {
        return engine.journeys().advance(mob, destination, options, null);
    }

    /** Stops and forgets the mob's journey. */
    public void abandonJourney() {
        engine.journeys().abandon(mob);
    }

    /** {@code true} while the mob is mid-journey. */
    public boolean isJourneying() {
        return engine.journeys().isJourneying(mob);
    }

    /** Navigates directly toward {@code target} (one path, no journey state). */
    public boolean navigateTo(Location target, double speed) {
        return MobNavigator.moveTo(mob, target, speed);
    }

    /** Navigates toward {@code target} but stops {@code stopDistance} short. */
    public boolean navigateToWithin(Location target, double stopDistance, double speed) {
        return MobNavigator.moveToWithin(mob, target, stopDistance, speed);
    }

    /** Stops the mob's current navigation. */
    public void stopNavigation() {
        MobNavigator.stop(mob);
    }

    /** Turns the mob's head toward {@code target}. */
    public void lookAt(Location target) {
        MobNavigator.lookToward(mob, target);
    }

    /** Whether a real path to {@code target} reaches it (Paper query, expensive). */
    public boolean canReach(Location target) {
        return MobNavigator.canReach(mob, target);
    }

    /**
     * A bounded world-work allowance for a stage that summons or builds outside
     * one engine pass. Capped by the engine's per-run limits.
     */
    public MobEditSession openEditSession(int editBudget, int spawnBudget) {
        return engine.openEditSession(editBudget, spawnBudget);
    }

    /**
     * Claims the mob for this pipeline under {@code activityId}, renewed by the
     * engine while the pipeline runs. A higher-priority module action can still
     * take the mob over, which ends the pipeline's usefulness but never leaves
     * it walking a mob somebody else owns.
     */
    public void claim(String activityId, Location anchor, double anchorRadius, long durationTicks) {
        engine.claimEntity(mob, activityId, anchor, anchorRadius, durationTicks, PIPELINE_PRIORITY);
    }

    /** Ends the pipeline's own claim when it still owns it. */
    public void releaseClaim(String activityId) {
        engine.releaseEntityActivity(mob, activityId);
    }

    /** {@code true} while {@code activityId} is the mob's live activity owner. */
    public boolean ownsClaim(String activityId) {
        return activityId != null && activityId.equals(engine.entityActivityId(mob));
    }

    /** Priority pipeline claims use: above ordinary content, below a forced takeover. */
    public static final int PIPELINE_PRIORITY = 1_000;

    /** Marks the pipeline failed with {@code reason}; the engine ends it after this pass. */
    public void fail(String reason) {
        if (failureReason == null) {
            failureReason = reason == null ? "stage reported failure" : reason;
        }
    }

    /** The failure reason this context has recorded, or {@code null}. */
    public String failureReason() {
        return failureReason;
    }

    // ------------------------------------------------------------------
    // Engine-side stage bookkeeping
    // ------------------------------------------------------------------

    void beginStage(String id, int index) {
        this.stageId = id;
        this.stageIndex = index;
        this.stageStartedGameTick = now();
        this.failureReason = null;
    }

    void resetFailure() {
        this.failureReason = null;
    }

    private long now() {
        return mob.getWorld() == null ? 0L : mob.getWorld().getGameTime();
    }
}
