package com.mineplus.infrastructure.mob.custom;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * Public API for Mineplus custom mobs — a peer of {@link
 * com.mineplus.infrastructure.mob.MobBehaviorApi}, obtained through
 * {@code PluginContext#customMobs()}.
 *
 * <p>A module registers a {@link CustomMobDefinition} once and then spawns it
 * by id. The Core owns everything that makes the mob a custom mob rather than a
 * weird vanilla entity: PDC identity, scoreboard discovery tag, AI isolation,
 * attribute tuning, resource-pack assets, per-part display rigs, chunk
 * restore, death cleanup and the behavior profile registration.</p>
 *
 * <p>Registration is owner-scoped: {@link #unregisterAll(Plugin)} is the
 * module-disable path. The API is safe to call when the subsystem is disabled
 * ({@link #isAvailable()} is {@code false}); registration and spawning then
 * return without touching the world.</p>
 *
 * <p>Thread model: registration and spawning are main-thread operations.</p>
 */
public interface CustomMobApi {

    /** True when the subsystem is enabled and running. */
    boolean isAvailable();

    /**
     * Registers a definition and its behavior profile. Idempotent per
     * {@code namespace:path} for the same owner; a different owner claiming an
     * existing id is a conflict.
     */
    void register(Plugin owner, CustomMobDefinition definition);

    /** Removes a definition, its behavior profile and its live mobs. */
    void unregister(Plugin owner, String definitionId);

    /** Removes every definition (and live mob) registered by {@code owner}. */
    void unregisterAll(Plugin owner);

    /** The registered definition for an id, or {@code null}. */
    CustomMobDefinition definition(String definitionId);

    /** Every registered definition, in registration order. */
    List<CustomMobDefinition> definitions();

    /** {@code true} when {@code id} is registered. */
    boolean isRegistered(String definitionId);

    /**
     * Spawns one custom mob at {@code location}. Returns {@code null} when the
     * subsystem is disabled, the definition is unknown, the chunk cannot be
     * loaded, or the server refused the base entity.
     */
    LivingEntity spawn(Location location, String definitionId);

    /** Same as {@link #spawn(Location, String)}, with a last-word configurator. */
    LivingEntity spawn(Location location, String definitionId, Consumer<LivingEntity> configurator);

    /** {@code true} when the entity carries a Mineplus custom-mob identity. */
    boolean isCustomMob(Entity entity);

    /** The custom mob id carried by {@code entity}, or {@code null}. */
    String customMobId(Entity entity);

    /** Number of live (tracked, loaded) mobs of one definition. */
    int liveCount(String definitionId);

    /** Every live mob of one definition (a snapshot). */
    List<LivingEntity> liveMobs(String definitionId);

    /** Removes every live mob of one definition. Returns how many were removed. */
    int removeLiveMobs(String definitionId);

    /**
     * Re-evaluates rendering for every live custom mob (after a pack reload or
     * a settings change): attaches rigs where possible and restores the base
     * entity where no rig can cover it.
     */
    void refreshRendering();

    /** Applies new subsystem settings ({@code CUSTOM_MOBS}). */
    void updateSettings(CustomMobSettings settings);

    /** Stops the renderer and forgets runtime state. Idempotent; entities persist. */
    void shutdown();
}
