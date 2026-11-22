package com.mineplus.infrastructure.mob.custom;

/**
 * Vanilla sound names a rigged mob plays at its lifecycle moments. Every field
 * is optional — an absent name plays nothing, so a silent mob stays silent.
 *
 * @param ambient idle sound, played on a randomized cadence while the rig is
 *               posed and a viewer is near
 * @param hurt   played once when the mob takes damage
 * @param death  played once when the mob begins its death sequence
 */
public record MobSoundSpec(String ambient, String hurt, String death) {

    public static final MobSoundSpec EMPTY = new MobSoundSpec(null, null, null);

    public MobSoundSpec {
        ambient = trimmed(ambient);
        hurt = trimmed(hurt);
        death = trimmed(death);
    }

    /** {@code true} when no sound was authored anywhere. */
    public boolean isEmpty() {
        return this == EMPTY || (ambient == null && hurt == null && death == null);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
