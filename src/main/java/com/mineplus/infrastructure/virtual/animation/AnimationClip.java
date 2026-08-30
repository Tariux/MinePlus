package com.mineplus.infrastructure.virtual.animation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One imported Blockbench animation ("clip").
 *
 * <p>Animators are keyed by <b>bone name</b> (the outliner group name), not by
 * the group UUID — the runtime, selectors, and module code address bones by
 * name. Keyframes per channel are sorted by time at import.
 */
public record AnimationClip(
        String name,
        LoopMode loop,
        float length,
        Map<String, BoneAnimation> animators
) {

    public AnimationClip {
        name = name == null || name.isBlank() ? "animation" : name;
        loop = loop == null ? LoopMode.ONCE : loop;
        animators = animators == null ? Map.of() : Map.copyOf(animators);
    }

    /** Per-channel keyframe tracks of one bone animator. Tracks may be empty. */
    public record BoneAnimation(
            List<Keyframe> rotation,
            List<Keyframe> position,
            List<Keyframe> scale
    ) {

        public BoneAnimation {
            rotation = sortAndCopy(rotation);
            position = sortAndCopy(position);
            scale = sortAndCopy(scale);
        }

        public boolean isEmpty() {
            return rotation.isEmpty() && position.isEmpty() && scale.isEmpty();
        }

        private static List<Keyframe> sortAndCopy(List<Keyframe> keyframes) {
            if (keyframes == null || keyframes.isEmpty()) {
                return List.of();
            }
            return keyframes.stream()
                    .sorted((a, b) -> Float.compare(a.time(), b.time()))
                    .toList();
        }
    }

    /** Lowercased lookup key for name-based clip resolution. */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
