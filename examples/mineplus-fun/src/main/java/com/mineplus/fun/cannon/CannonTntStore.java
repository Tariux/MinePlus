package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.state.TypedState;

/**
 * Persistent per-cannon ammunition store: TNT and fire charges (fireballs).
 *
 * <p>Counts live in the instance's {@code stateData} through the Core's
 * {@link TypedState} typed view, which the Core's persistence layer snapshots
 * — loaded ammunition therefore survives restarts together with the multiblock
 * itself. A cannon fires fireballs first: whenever a fire charge is loaded,
 * shots consume it instead of TNT (see {@code CannonProjectiles}).
 */
public final class CannonTntStore {

    private CannonTntStore() {
    }

    /** Returns the amount of TNT currently loaded into the cannon (never negative). */
    public static int load(MultiBlockInstance instance) {
        return TypedState.of(instance).getInt(CannonKeys.STATE_TNT_COUNT, 0);
    }

    /** Stores the amount of TNT currently loaded into the cannon. */
    public static void save(MultiBlockInstance instance, int amount) {
        TypedState.of(instance).setInt(CannonKeys.STATE_TNT_COUNT, Math.max(0, amount));
    }

    /** Returns the amount of fire charges (fireballs) currently loaded (never negative). */
    public static int loadFireballs(MultiBlockInstance instance) {
        return TypedState.of(instance).getInt(CannonKeys.STATE_FIREBALL_COUNT, 0);
    }

    /** Stores the amount of fire charges (fireballs) currently loaded. */
    public static void saveFireballs(MultiBlockInstance instance, int amount) {
        TypedState.of(instance).setInt(CannonKeys.STATE_FIREBALL_COUNT, Math.max(0, amount));
    }

    /** True when the cannon should fire a fireball on its next shot (fire charges take priority). */
    public static boolean hasFireballLoaded(MultiBlockInstance instance) {
        return loadFireballs(instance) > 0;
    }
}
