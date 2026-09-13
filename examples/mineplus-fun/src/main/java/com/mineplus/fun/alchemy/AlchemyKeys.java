package com.mineplus.fun.alchemy;

/**
 * Constants for the alchemy-table pack-block showcase.
 *
 * <p>The multiblock model key is the engine's derived key
 * ({@code <typeId>_lvl_<level>}): the pack block registration must bind to the
 * same key {@code ModelRenderingManager} resolves at render time.</p>
 */
public final class AlchemyKeys {

    private AlchemyKeys() {
    }

    public static final String NAMESPACE = "fun";
    public static final String MACHINE_ID = "alchemy_table";
    public static final String BLOCK_ID = "alchemy_table";
    public static final int LEVEL = 1;
    /** Must equal {@code ModelRenderingManager.buildModelKey(MACHINE_ID, LEVEL)}. */
    public static final String MODEL_KEY = MACHINE_ID + "_lvl_" + LEVEL;
    /**
     * Key the model file loads under (its stem in {@code plugins/Mineplus/models/}).
     * The pack geometry/texture assets attach under this key at reload time;
     * {@link #MODEL_KEY} only exists after the first render.
     */
    public static final String GEOMETRY_MODEL_KEY = "alchemy-table";
}
