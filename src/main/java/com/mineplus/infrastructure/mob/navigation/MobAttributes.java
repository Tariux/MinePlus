package com.mineplus.infrastructure.mob.navigation;

import java.lang.reflect.Field;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;

/**
 * The mob attributes movement decisions depend on, read once per question.
 *
 * <p>The important one is the follow range: vanilla caps a ground mob's single
 * path search at roughly its follow range, so a waypoint further away than that
 * produces "no path" however clear the terrain is. That is why a fixed 32-block
 * journey leg works for a zombie (35) and silently parks a villager (16) — the
 * leg has to be derived from the mob, not from a constant.
 *
 * <p>The attribute constant was renamed in 1.21.3 ({@code GENERIC_FOLLOW_RANGE}
 * to {@code FOLLOW_RANGE}), so both spellings are resolved reflectively and the
 * surviving one is cached. Supplied games (Spigot 1.20-) still carry the
 * generic name.
 *
 * <p>Thread model: main thread only (attribute access is).
 */
public final class MobAttributes {

    /** A safe answer for a mob whose follow range cannot be read. */
    private static final double DEFAULT_FOLLOW_RANGE = 16.0;
    private static final double MIN_PATH_RANGE = 8.0;
    private static final double MAX_PATH_RANGE = 48.0;

    private static volatile Attribute followRange;
    private static volatile boolean resolved;

    private MobAttributes() {
    }

    /** The mob's follow range, or {@value #DEFAULT_FOLLOW_RANGE} when unreadable. */
    public static double followRange(Mob mob) {
        if (mob == null) {
            return DEFAULT_FOLLOW_RANGE;
        }
        try {
            Attribute attribute = followRangeAttribute();
            if (attribute != null) {
                AttributeInstance instance = mob.getAttribute(attribute);
                if (instance != null) {
                    return instance.getValue();
                }
            }
        } catch (Throwable ignored) {
            // A server that renames or removes the attribute again falls back
            // to the conservative default.
        }
        return DEFAULT_FOLLOW_RANGE;
    }

    /**
     * The distance one path search may span for this mob: its follow range
     * with a two-block margin, bounded so a modded 128-range mob does not turn
     * every repath into a world-wide search.
     */
    public static double pathRange(Mob mob) {
        return Math.max(MIN_PATH_RANGE, Math.min(MAX_PATH_RANGE, followRange(mob) - 2.0));
    }

    private static Attribute followRangeAttribute() {
        if (!resolved) {
            synchronized (MobAttributes.class) {
                if (!resolved) {
                    Attribute found = field("GENERIC_FOLLOW_RANGE");
                    if (found == null) {
                        found = field("FOLLOW_RANGE");
                    }
                    followRange = found;
                    resolved = true;
                }
            }
        }
        return followRange;
    }

    private static Attribute field(String name) {
        try {
            Field field = Attribute.class.getField(name);
            Object value = field.get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (Throwable missing) {
            return null;
        }
    }
}
