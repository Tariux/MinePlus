package com.mineplus.fun.cabinet;

public final class CabinetKeys {

    public static final String MACHINE_ID = "cabinet";
    /** Pack-block twin of {@link #MACHINE_ID}: same models, {@code renderBackend: pack} + {@code renderKind: block}. */
    public static final String PACK_MACHINE_ID = "cabinet_pack";
    public static final String GUI_KEY = "cabinet_storage";

    /** Pack block ids (one per level's model). */
    public static final String PACK_BLOCK_CLOSED_ID = "cabinet_closed";
    public static final String PACK_BLOCK_OPEN_ID = "cabinet_open";

    /** Level 1 renders the closed cabinet model (placed/default state). */
    public static final int LEVEL_CLOSED = 1;
    /** Level 2 renders the open cabinet model (doors open, storage reachable). */
    public static final int LEVEL_OPEN = 2;

    /** True for either cabinet type (virtual/texel or pack block). */
    public static boolean isCabinet(String typeId) {
        return MACHINE_ID.equals(typeId) || PACK_MACHINE_ID.equals(typeId);
    }

    public static final int STORAGE_SLOTS = 18;
    public static final String STATE_SLOT_PREFIX = "cabinet_slot_";

    private CabinetKeys() {
    }
}
