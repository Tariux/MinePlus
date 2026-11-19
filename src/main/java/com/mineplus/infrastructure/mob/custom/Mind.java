package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.MobProfile;
import java.util.function.Consumer;
import org.bukkit.entity.EntityType;

/**
 * The authoring-time description of who drives a custom mob — the one concept
 * both authoring paths share.
 *
 * <ul>
 *   <li>{@link #vanillaAi()} — the vanilla server engine keeps the base type's
 *       own goals: the mob walks, hunts, swings and dies exactly like that mob,
 *       and the Mineplus layer only dresses it up. The behavior engine never
 *       sees it.</li>
 *   <li>{@link #vanillaGoalsOf(EntityType)} — the same, but inheriting a named
 *       donor type's goals (which must be the mob's base type; a mismatched
 *       donor is rejected and the mob stays isolated).</li>
 *   <li>{@link #coreProfile(Consumer)} — the Core owns the mind: vanilla goals
 *       and Brain are isolated away and every move comes from the authored
 *       {@link MobProfile}.</li>
 *   <li>{@link #coreIdle()} — a Core-owned mob with no profile: it stands still
 *       unless a module moves it, which is the right base for guard/wander
 *       style profiles a module adds later.</li>
 * </ul>
 *
 * <p>Applied through {@link CustomMobDefinition.Builder#mind(Mind)}. The older
 * builder calls ({@code vanillaAi()}, {@code inheritGoalsFrom(...)},
 * {@code behavior(...)}) remain the same mechanism spelled per-builder-method
 * and keep working.</p>
 */
public final class Mind {

    /** Who drives the mob. */
    public enum Kind {
        /** The vanilla server engine (goal/target selectors of the donor type). */
        VANILLA,
        /** The Core behavior engine (vanilla AI isolated away). */
        CORE
    }

    private final Kind kind;
    private final EntityType donor;
    private final Consumer<MobProfile.Builder> behavior;

    private Mind(Kind kind, EntityType donor, Consumer<MobProfile.Builder> behavior) {
        this.kind = kind;
        this.donor = donor;
        this.behavior = behavior;
    }

    /** The base type's own vanilla AI, un-isolated. */
    public static Mind vanillaAi() {
        return new Mind(Kind.VANILLA, null, null);
    }

    /** A named donor type's vanilla goals (must equal the mob's base type). */
    public static Mind vanillaGoalsOf(EntityType donor) {
        return new Mind(Kind.VANILLA, donor, null);
    }

    /** A Core-owned mind driven by the authored profile. */
    public static Mind coreProfile(Consumer<MobProfile.Builder> behavior) {
        return new Mind(Kind.CORE, null, behavior);
    }

    /** A Core-owned mind with no profile (idle; the module moves it). */
    public static Mind coreIdle() {
        return new Mind(Kind.CORE, null, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The donor type whose vanilla goals are kept, or {@code null} for the base type. */
    public EntityType donor() {
        return donor;
    }

    /** The profile customizer for a {@link Kind#CORE} mind, or {@code null}. */
    public Consumer<MobProfile.Builder> behavior() {
        return behavior;
    }
}
