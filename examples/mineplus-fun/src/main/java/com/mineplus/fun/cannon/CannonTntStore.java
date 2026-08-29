package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;

/**
 * Persistent per-cannon ammunition store: TNT and fire charges (fireballs).
 *
 * <p>Counts live in the instance's {@code stateData}, which the Core's
 * persistence layer snapshots — loaded ammunition therefore survives restarts
 * together with the multiblock itself. A cannon fires fireballs first: whenever
 * a fire charge is loaded, shots consume it instead of TNT (see
 * {@code CannonProjectiles}).
 */
public final class CannonTntStore {

    private CannonTntStore() {
    }

    /** Returns the amount of TNT currently loaded into the cannon (never negative). */
    public static int load(MultiBlockInstance instance) {
        return parseCount(instance, CannonKeys.STATE_TNT_COUNT);
    }

    /** Stores the amount of TNT currently loaded into the cannon. */
    public static void save(MultiBlockInstance instance, int amount) {
        instance.mutableStateData().put(CannonKeys.STATE_TNT_COUNT, String.valueOf(clean(amount)));
    }

    /** Returns the amount of fire charges (fireballs) currently loaded (never negative). */
    public static int loadFireballs(MultiBlockInstance instance) {
        return parseCount(instance, CannonKeys.STATE_FIREBALL_COUNT);
    }

    /** Stores the amount of fire charges (fireballs) currently loaded. */
    public static void saveFireballs(MultiBlockInstance instance, int amount) {
        instance.mutableStateData().put(CannonKeys.STATE_FIREBALL_COUNT, String.valueOf(clean(amount)));
    }

    /** True when the cannon should fire a fireball on its next shot (fire charges take priority). */
    public static boolean hasFireballLoaded(MultiBlockInstance instance) {
        return loadFireballs(instance) > 0;
    }

    private static int parseCount(MultiBlockInstance instance, String key) {
        String raw = instance.stateData().get(key);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int clean(int amount) {
        return Math.max(0, amount);
    }
}
