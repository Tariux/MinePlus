package com.mineplus.infrastructure.virtual.animation;

/**
 * Addresses animation targets by name. Selectors are the "external hook
 * interface" addressing scheme: they resolve against clip names, bone names,
 * or the entire animated model.
 *
 * <ul>
 *   <li>{@link #animation(String)} — one clip by name.</li>
 *   <li>{@link #bone(String)} — every clip animating the named bone (group),
 *       and (for enable/disable/trigger) the bone's own tracks within them.</li>
 *   <li>{@link #all()} — every clip of the instance's model.</li>
 * </ul>
 */
public record AnimationSelector(Kind kind, String target) {

    public enum Kind {
        ANIMATION,
        BONE,
        ALL
    }

    public static AnimationSelector animation(String name) {
        return new AnimationSelector(Kind.ANIMATION, name);
    }

    public static AnimationSelector bone(String name) {
        return new AnimationSelector(Kind.BONE, name);
    }

    public static AnimationSelector all() {
        return new AnimationSelector(Kind.ALL, null);
    }

    public AnimationSelector {
        kind = kind == null ? Kind.ALL : kind;
        target = target == null || target.isBlank() ? null : target.trim();
        if (kind == Kind.ALL) {
            target = null;
        }
    }
}
