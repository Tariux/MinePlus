package com.mineplus.infrastructure.mob;

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

    /** Applies new engine settings ({@code MOB_BEHAVIOR} section). */
    void updateSettings(MobBehaviorSettings settings);

    /** The active engine settings. */
    MobBehaviorSettings settings();

    /** Telemetry summary (runs, fired events, per-action counts, deferrals). */
    String telemetrySummary();

    /** Stops the run task. Called by the Core's disable path; idempotent. */
    void shutdown();
}
