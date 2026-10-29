package com.mineplus.gun.runtime;

import java.util.Locale;

/**
 * How a weapon discharges. Drives the fire-flow state machine in
 * {@link WeaponRuntime}.
 */
public enum FireMode {
    /** One shot per press. */
    SEMI,
    /** Continuous fire while the trigger is held, gated to the weapon's rpm. */
    AUTO,
    /** {@code burstCount} rounds per press at the weapon's rpm. */
    BURST,
    /** Pump action: a full cycle cooldown between shots. */
    PUMP,
    /** Bolt action: a long cycle cooldown between shots. */
    BOLT;

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
