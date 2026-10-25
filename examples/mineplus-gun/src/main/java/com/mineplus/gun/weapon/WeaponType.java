package com.mineplus.gun.weapon;

import java.util.Locale;

/** Coarse weapon class, used for shop grouping and metadata. */
public enum WeaponType {
    PISTOL,
    SMG,
    RIFLE,
    SHOTGUN,
    SNIPER,
    MELEE;

    /** Human-facing category label ("Pistols"). */
    public String categoryLabel() {
        return switch (this) {
            case PISTOL -> "Pistols";
            case SMG -> "SMGs";
            case RIFLE -> "Rifles";
            case SHOTGUN -> "Shotguns";
            case SNIPER -> "Snipers";
            case MELEE -> "Melee";
        };
    }

    public static WeaponType fromKey(String key, WeaponType fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        try {
            return WeaponType.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
