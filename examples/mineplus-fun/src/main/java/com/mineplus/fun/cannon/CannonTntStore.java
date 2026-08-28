package com.mineplus.fun.cannon;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;

/**
 * Persistent per-cannon TNT ammunition counter.
 *
 * <p>The count is stored inside the instance's {@code stateData}, which the Core's
 * persistence layer snapshots — loaded ammunition therefore survives restarts
 * together with the multiblock itself.
 */
public final class CannonTntStore {

    private CannonTntStore() {
    }

    /** Returns the amount of TNT currently loaded into the cannon (never negative). */
    public static int load(MultiBlockInstance instance) {
        String raw = instance.stateData().get(CannonKeys.STATE_TNT_COUNT);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** Stores the amount of TNT currently loaded into the cannon. */
    public static void save(MultiBlockInstance instance, int amount) {
        instance.mutableStateData().put(CannonKeys.STATE_TNT_COUNT, String.valueOf(Math.max(0, amount)));
    }
}
