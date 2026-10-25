package com.mineplus.infrastructure.mob;

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
    public String telemetrySummary() {
        return engine.telemetry().summary() + "\nregistered types=" + engine.profileTypeCount();
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
