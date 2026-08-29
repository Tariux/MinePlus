package com.mineplus.fun.cannon;

public final class CannonKeys {

    public static final String MACHINE_ID = "cannon";
    public static final String GUI_KEY = "cannon_gui";
    public static final String STATE_TNT_COUNT = "cannon_tnt_count";
    public static final String STATE_FIREBALL_COUNT = "cannon_fireball_count";

    /** Level whose model and hook behaviour unlock the gunner's seat (aimed fire). */
    public static final int LEVEL_AIMED = 2;

    /** PDC marker (byte 1) on the "Cannon Lanyard" bow handed to a mounted gunner. */
    public static final String PDC_LANYARD = "cannon_lanyard";

    /** PDC marker (byte 1) on the "Cannon Match" arrow that lets the lanyard bow draw. */
    public static final String PDC_MATCH = "cannon_match";

    /** PDC marker on a gunner's-seat armor stand; value is the cannon instance id. */
    public static final String PDC_SEAT = "cannon_seat";

    private CannonKeys() {
    }
}
