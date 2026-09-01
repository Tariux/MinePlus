package com.mineplus.fun.cabinet;

public final class CabinetKeys {

    public static final String MACHINE_ID = "cabinet";
    public static final String GUI_KEY = "cabinet_storage";

    /** Level 1 renders the closed cabinet model (placed/default state). */
    public static final int LEVEL_CLOSED = 1;
    /** Level 2 renders the open cabinet model (doors open, storage reachable). */
    public static final int LEVEL_OPEN = 2;

    public static final int STORAGE_SLOTS = 18;
    public static final String STATE_SLOT_PREFIX = "cabinet_slot_";

    private CabinetKeys() {
    }
}
