package com.mineplus.mobfun;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * One entry of the MobFun showcase bench: a toggleable behavior package that
 * can spawn a representative mob for testing.
 *
 * <p>Two implementations exist: {@link MobFunShowcase} registers a behavior
 * profile on an existing vanilla mob, and {@link CustomMobShowcase} registers a
 * whole custom mob (identity, model and behavior) through the Core's custom-mob
 * API. The command layer treats both the same.</p>
 */
public interface MobFunShowcaseEntry {

    /** Stable id used by {@code /mobfun <id>}. */
    String id();

    /** Human-readable label for status output. */
    String label();

    /** The mob type the showcase profiles (the base type for a custom mob). */
    EntityType entityType();

    boolean isEnabled();

    void enable();

    void disable();

    /** Registers with the Core on module start. */
    default void start() {
        enable();
    }

    /** Unregisters from the Core on module stop. */
    default void stop() {
        disable();
    }

    /**
     * Spawns one representative mob at {@code location}: a plain entity for a
     * behavior showcase, a fully initialized custom mob (identity, attributes,
     * isolation, rig) for a custom showcase.
     *
     * @return the spawned mob, or {@code null} when spawning was refused
     */
    LivingEntity spawn(Location location);

    /** One status line ({@code "  Label (type): ON"}). */
    String statusLine();
}
