package com.mineplus.infrastructure.mob;

import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.StructureLookup;
import com.mineplus.infrastructure.mob.pipeline.MobPipeline;
import com.mineplus.infrastructure.mob.pipeline.MobPipelineHandle;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * Core-side implementation of {@link MobBehaviorApi}; a thin, main-thread
 * checking facade over {@link MobBehaviorEngine}.
 */
public final class MineplusMobBehaviorApi implements MobBehaviorApi {

    private final MobBehaviorEngine engine;

    /** Called by the Core's {@code PluginContext#bootstrap}. */
    public MineplusMobBehaviorApi(MobBehaviorEngine engine) {
        this.engine = engine;
    }

    @Override
    public void register(Plugin owner, MobProfile profile) {
        requireMainThread();
        engine.register(owner, profile);
    }

    @Override
    public void unregister(Plugin owner, MobProfile profile) {
        requireMainThread();
        engine.unregister(owner, profile);
    }

    @Override
    public void unregisterAll(Plugin owner) {
        requireMainThread();
        engine.unregisterAll(owner);
    }

    @Override
    public boolean isRegistered(Plugin owner) {
        return engine.hasProfiles(owner);
    }

    @Override
    public BehaviorFireResult forceFire(Plugin owner, LivingEntity entity, boolean ignoreConditions) {
        requireMainThread();
        return engine.forceFire(owner, entity, ignoreConditions);
    }

    @Override
    public void updateSettings(MobBehaviorSettings settings) {
        engine.updateSettings(settings);
    }

    @Override
    public MobBehaviorSettings settings() {
        return engine.settings();
    }

    @Override
    public String activeActivity(LivingEntity entity) {
        return engine.activityIdOf(entity);
    }

    @Override
    public boolean clearActivity(LivingEntity entity, String actionId) {
        requireMainThread();
        return engine.releaseActivity(entity, actionId);
    }

    @Override
    public String telemetrySummary() {
        return engine.telemetry().summary() + "\nregistered types=" + engine.profileTypeCount()
                + "\njourneys=" + engine.journeys().activeCount()
                + ", crews=" + engine.crews().activeCount()
                + ", pipelines=" + engine.pipelines().activeCount()
                + ", cached regions=" + engine.structures().cachedRegions();
    }

    @Override
    public JourneyRunner journeys() {
        return engine.journeys();
    }

    @Override
    public StructureLookup structures() {
        return engine.structures();
    }

    @Override
    public com.mineplus.infrastructure.mob.group.MobCrewService crews() {
        return engine.crews();
    }

    @Override
    public com.mineplus.infrastructure.mob.group.SquadManager squads() {
        return engine.squads();
    }

    @Override
    public java.util.List<String> journeySnapshot(int maxLines) {
        return engine.journeys().snapshot(maxLines);
    }

    @Override
    public java.util.List<String> crewSnapshot(int maxLines) {
        return engine.crews().snapshot(maxLines);
    }

    @Override
    public MobPipelineHandle assignPipeline(Plugin owner, LivingEntity entity, MobPipeline pipeline) {
        requireMainThread();
        return engine.pipelines().assign(owner, entity, pipeline);
    }

    @Override
    public MobPipelineHandle pipelineOf(LivingEntity entity) {
        return engine.pipelines().handle(entity);
    }

    @Override
    public java.util.List<String> pipelineSnapshot(int maxLines) {
        return engine.pipelines().snapshot(maxLines);
    }

    @Override
    public MobEditSession openEditSession(int editBudget, int spawnBudget) {
        requireMainThread();
        return engine.openEditSession(editBudget, spawnBudget);
    }

    @Override
    public void shutdown() {
        engine.shutdown();
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("MobBehaviorApi registration must happen on the main server thread");
        }
    }
}
