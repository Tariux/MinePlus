package com.mineplus.infrastructure.mob.navigation;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
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
 * generic name. The same resolution also serves custom mob attribute tuning
 * ({@link #set(LivingEntity, String, double)}), so no module has to know which
 * attribute spelling its server uses.
 *
 * <p>Thread model: main thread only (attribute access is).
 */
public final class MobAttributes {

    /** A safe answer for a mob whose follow range cannot be read. */
    private static final double DEFAULT_FOLLOW_RANGE = 16.0;
    private static final double MIN_PATH_RANGE = 8.0;
    private static final double MAX_PATH_RANGE = 48.0;

    /** Canonical name (without the GENERIC_ prefix) -> resolved attribute. */
    private static final Map<String, Optional<Attribute>> ATTRIBUTES = new ConcurrentHashMap<>();

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

    /**
     * Sets a base attribute value by canonical name ({@code MAX_HEALTH},
     * {@code MOVEMENT_SPEED}, {@code ATTACK_DAMAGE}, {@code FOLLOW_RANGE},
     * {@code ARMOR}, {@code KNOCKBACK_RESISTANCE}). The version-specific
     * spelling ({@code GENERIC_X} on older servers, {@code X} on 1.21.3+) is
     * resolved and cached. A type without that attribute is left untouched.
     *
     * @return {@code true} when the attribute existed and was set
     */
    public static boolean set(LivingEntity entity, String canonicalName, double value) {
        if (entity == null || canonicalName == null || canonicalName.isBlank()) {
            return false;
        }
        Attribute attribute = resolveAttribute(canonicalName);
        if (attribute == null) {
            return false;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        try {
            instance.setBaseValue(value);
            return true;
        } catch (Throwable rejected) {
            return false;
        }
    }

    /** The current value of a canonical attribute, or {@code fallback} when unreadable. */
    public static double get(LivingEntity entity, String canonicalName, double fallback) {
        if (entity == null || canonicalName == null) {
            return fallback;
        }
        Attribute attribute = resolveAttribute(canonicalName);
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /**
     * Resolves a canonical attribute name ({@code MAX_HEALTH}, …) against this
     * runtime, trying the GENERIC_ spelling first. {@code null} when the
     * runtime enum has no such attribute — never throws. Also tolerates the
     * prefixed spelling and any case, so tables written for one era keep
     * working on the other.
     */
    public static Attribute resolveAttribute(String canonicalName) {
        if (canonicalName == null) {
            return null;
        }
        String key = canonicalName.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        return ATTRIBUTES.computeIfAbsent(key, name -> {
            Attribute found = field("GENERIC_" + name);
            if (found == null) {
                found = field(name);
            }
            return Optional.ofNullable(found);
        }).orElse(null);
    }

    private static Attribute followRangeAttribute() {
        return resolveAttribute("FOLLOW_RANGE");
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
