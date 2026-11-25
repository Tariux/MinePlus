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

    /** Animation clips authored in {@code tea.bbmodel}. */
    public static final String ANIM_DRINK = "drink_firstperson";
    public static final String ANIM_FILL = "fill";
    public static final String ANIM_EMPTY = "empty";
    public static final String ANIM_STEAM = "steam_idle";

    /** Clips played by {@code /tea debug}, steam last (it is the looping one). */
    public static final List<String> ANIMATIONS = List.of(ANIM_DRINK, ANIM_FILL, ANIM_EMPTY, ANIM_STEAM);

    private TeaKeys() {
    }
}
