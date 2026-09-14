package com.mineplus.gun.runtime;

import java.util.Locale;

/**
 * How a weapon discharges. Drives the fire-flow state machine in
 * {@link GunRuntime}.
 */
public enum FireMode {
    /** One shot per press. */
    SEMI,
    /** Continuous fire while held, gated to the weapon's rpm. */
    AUTO,
    /** {@code burstCount} rounds per press at the weapon's rpm. */
    BURST,
    /** Pump action: a full cycle cooldown between shots. */
    PUMP,
    /** Bolt action: a long cycle cooldown between shots. */
    BOLT,
    /** Melee arc sweep; no ammunition. */
    MELEE,
    /** Thrown projectile (grenade). */
    THROW;

    public static FireMode fromKey(String key, FireMode fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return FireMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
