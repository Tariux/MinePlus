package com.mineplus.infrastructure.mob;

import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.StructureLookup;
import com.mineplus.infrastructure.mob.pipeline.MobPipeline;
import com.mineplus.infrastructure.mob.pipeline.MobPipelineHandle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * Public extension API of the mob behavior engine. Dependent plugins obtain it
 * via {@code MineplusPlugin#getPluginContext()#mobBehaviorApi()} and register
 * {@link MobProfile}s describing which {@link BehaviorAction}s their mobs
 * perform and how often.
 *
 * <p>Registration is additive and owner-scoped: a module unregisters cleanly
 * with {@link #unregisterAll(Plugin)} on its own disable, and the engine keeps
 * serving other modules' profiles untouched.
 */
public interface MobBehaviorApi {

    /**
     * Registers a behavior profile for one entity type. The engine starts
     * lazily on the first registration.
     *
     * @param owner   the registering module (used for cleanup scope and logging)
     * @param profile the behavior set for one entity type
     */
    void register(Plugin owner, MobProfile profile);

    /** Removes one specific profile previously registered by {@code owner}. */
    void unregister(Plugin owner, MobProfile profile);

    /** Removes every profile registered by {@code owner}. */
    void unregisterAll(Plugin owner);

    /** {@code true} when {@code owner} currently holds at least one profile. */
    boolean isRegistered(Plugin owner);

    /**
     * Forces one direct firing pass on a specific entity: every action
     * registered by {@code owner} for the entity's type runs immediately with
     * the odds gate bypassed — a testing/verification hook, not an evaluation.
     * Profile conditions still gate the actions unless
     * {@code ignoreConditions} is {@code true}; the returned report shows each
     * action's outcome either way. Works even when the engine loop is
     * disabled. Main thread only.
     *
     * @param owner            the module whose registered profiles apply
     * @param entity           the entity to fire on
     * @param ignoreConditions {@code true} to bypass profile conditions
     * @return per-action outcome report
     */
    BehaviorFireResult forceFire(Plugin owner, LivingEntity entity, boolean ignoreConditions);

    /** Applies new engine settings ({@code MOB_BEHAVIOR} section). */
    void updateSettings(MobBehaviorSettings settings);

    /** The active engine settings. */
    MobBehaviorSettings settings();

    /**
     * The action id of the entity's current exclusive activity claim, or
     * {@code null}. While a claim is live, only the owning action runs on the
     * entity and every other action of any module skips it.
     */
    String activeActivity(LivingEntity entity);

    /**
     * Ends the entity's activity claim when it is owned by {@code actionId};
     * {@code true} when the claim was dropped.
     */
    boolean clearActivity(LivingEntity entity, String actionId);

    /**
     * Telemetry summary (runs, fired events, per-action counts, edits, spawns,
     * deferrals).
     */
    String telemetrySummary();

    // ------------------------------------------------------------------
    // Shared scenario services (the primitives a showcase re-implements badly)
    // ------------------------------------------------------------------

    /**
     * The engine's long-walk runner. Actions normally go through
     * {@link BehaviorContext#advanceJourney}; this is for scheduled module tasks
     * that drive a mob between engine runs.
     */
    JourneyRunner journeys();

    /** The engine's cached, cost-gated structure discovery. */
    StructureLookup structures();

    /** The engine's crew (group-movement) registry. */
    com.mineplus.infrastructure.mob.group.MobCrewService crews();

    /**
     * The engine's formation/role registry for crews: a crew's shape around its
     * formation point (wedge, pincer, ring) and what each member does in it.
     */
    com.mineplus.infrastructure.mob.group.SquadManager squads();

    /** One line per live journey, for status commands. */
    java.util.List<String> journeySnapshot(int maxLines);

    /** One line per live crew, for status commands. */
    java.util.List<String> crewSnapshot(int maxLines);

    // ------------------------------------------------------------------
    // Pipelines (multi-stage scenarios attached to one mob)
    // ------------------------------------------------------------------

    /**
     * Attaches a {@link MobPipeline} to {@code entity}: locate, march, act —
     * the scenario form of behavior, with per-stage timeouts and a shared
     * blackboard. While it runs, the pipeline advances every engine pass and
     * every ordinary action skips the entity.
     *
     * <p>Assigning a pipeline to a mob that already runs one replaces it.
     * Main thread only.
     *
     * @return the handle, or {@code null} when the entity is not a live mob
     */
    MobPipelineHandle assignPipeline(Plugin owner, LivingEntity entity, MobPipeline pipeline);

    /** The pipeline currently driving {@code entity}, or {@code null}. */
    MobPipelineHandle pipelineOf(LivingEntity entity);

    /** One line per live pipeline (mob, scenario and current stage). */
    java.util.List<String> pipelineSnapshot(int maxLines);

    /**
     * A bounded world-write allowance for a scenario that keeps building after
     * the evaluation that started it (a tower raised block by block over
     * scheduled ticks). The grant is capped by the engine's per-run edit limit —
     * asking for more does not earn more. Main thread only.
     */
    default MobEditSession openEditSession(int editBudget) {
        return openEditSession(editBudget, 0);
    }

    /**
     * The same allowance with a summon allowance attached, for a scenario whose
     * scheduled work ends in an entity (the golem a column becomes). Both
     * budgets are capped by the engine's per-run limits.
     */
    MobEditSession openEditSession(int editBudget, int spawnBudget);

    /** Stops the engine run loop and releases every claim. Idempotent. */
    void shutdown();
}
