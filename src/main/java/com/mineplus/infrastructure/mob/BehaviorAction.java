package com.mineplus.infrastructure.mob;

/**
 * One world-touching behavior an entity can perform. Implementations must be
 * stateless: all per-evaluation state (entity, foot/ground blocks, biome,
 * random source, edit budget) is provided through {@link BehaviorContext},
 * which the engine refreshes for every single evaluation — never shared
 * static state, never assumptions about the previous evaluation.
 *
 * <p>Actions execute on the main server thread. Use {@link BehaviorContext}
 * helpers for every world write so the engine's per-run edit budget is
 * enforced and telemetry stays accurate.
 */
public interface BehaviorAction {

    /** Stable identifier used for telemetry and logging (e.g. {@code "eat_plants"}). */
    String id();

    /**
     * Executes this action against the current context. Cheap self-checks are
     * expected to return quickly; anything not safe to perform here (wrong
     * block underfoot, too bright, too crowded) just returns without writing.
     *
     * @param context per-evaluation entity view, refreshed by the engine
     * @return {@code true} when the action changed the world in a way worth counting
     */
    boolean execute(BehaviorContext context);

    /**
     * {@code true} when this action is a continuous process: while it owns the
     * entity, the per-fire odds gate is bypassed and the action evaluates
     * every engine stagger, so movement and attack cadence are steady rather
     * than probabilistic. Default {@code false}.
     */
    default boolean continuous() {
        return false;
    }

    /**
     * How badly this action wants to run right now, in {@code [0, 1]}. The
     * engine tries a profile's actions most-urgent-first, with a small
     * stickiness bonus for the action that ran last, so drives (fear, anger)
     * can pre-empt routine behavior without profiles having to encode the
     * ordering in registration order. Default {@code 0.5} keeps any action
     * that does not care on par with the others.
     */
    default double urgency(BehaviorContext context) {
        return 0.5;
    }
}
