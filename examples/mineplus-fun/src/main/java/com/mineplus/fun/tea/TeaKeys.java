package com.mineplus.fun.tea;

import java.util.List;

/** Constants shared by the Tea feature's classes. */
public final class TeaKeys {

    /** Model key of the tea cup (installed as {@code models/tea.bbmodel}). */
    public static final String MODEL_KEY = "tea";

    /** Pack namespace of the tea cup item. */
    public static final String NAMESPACE = "fun";

    /** Pack item id of the tea cup handed out by {@code /tea give}. */
    public static final String TEA_ITEM = "tea_cup";

    /**
     * Animation clips authored in {@code tea.bbmodel}, mapped onto the Core's
     * client-clock vocabulary at bake time ({@code drink_firstperson} →
     * {@code reload}, {@code steam_idle} → {@code fire}; see
     * {@link TeaBaker}). {@code fill}/{@code empty} animate only the tea
     * surface's scale, which an item frame cannot express, so they never bake
     * and are not listed.
     */
    public static final String ANIM_DRINK = "reload";
    public static final String ANIM_STEAM = "fire";

    /** Authored names, shown to the player by {@code /tea}. */
    public static final List<String> ANIMATIONS = List.of("drink_firstperson", "steam_idle");

    private TeaKeys() {
    }
}
