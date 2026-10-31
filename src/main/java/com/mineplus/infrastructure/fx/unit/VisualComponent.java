package com.mineplus.infrastructure.fx.unit;

/**
 * One ordered piece of a {@link VisualUnit}: a decal, a particle burst, a
 * sound cue or a light flash.
 *
 * <p>Components are advanced by {@link VisualUnitService} on the main thread,
 * one call per tick, so a component may touch the world directly. A component
 * that finishes returns {@code false} from {@link #tick} and is cancelled
 * exactly once; a unit that times out or is dropped by the budget cancels
 * every remaining component.</p>
 */
public interface VisualComponent {

    /** Called once at spawn, on the main thread. Must be cheap and never throw. */
    default void start(VisualUnitService service) {
    }

    /**
     * Advances one tick.
     *
     * @return {@code true} to keep the component alive; {@code false} when it
     *         finished (it is cancelled immediately after)
     */
    default boolean tick(VisualUnitService service) {
        return false;
    }

    /** Releases anything held (pooled displays, for example). Must be idempotent. */
    default void cancel(VisualUnitService service) {
    }

    /** Lower-priority components are dropped first when the budget is exceeded. */
    default int priority() {
        return 0;
    }
}
