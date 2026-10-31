package com.mineplus.infrastructure.mob.custom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.joml.Vector3f;

/**
 * One authored animation clip of a custom mob model: per-part rotation and
 * position channels, sampled by the rig renderer.
 *
 * <p>The clip is the authored half of a Blockbench animation (a
 * {@code .bbmodel}'s {@code animations[]} entry). Part names are the same
 * normalized names {@link MobPart} uses, so a clip binds to parts by name and a
 * model with no clip renders exactly as before.</p>
 *
 * <p>Units are the model's own: rotations in degrees, positions in model
 * pixels, both in the y-up model space the geometry is authored in. The
 * renderer converts them (rotation to a quaternion, position to blocks) when it
 * poses a bone.</p>
 *
 * <p>Sampling is linear between keys (a step key holds its value); a time
 * before the first or after the last key clamps to that key, and looping is the
 * caller's business ({@link #wrap(float)}). Molang expressions are not
 * evaluated: an unparsable value becomes {@code 0}, so a clip authored with
 * expressions still plays its numeric keys instead of refusing to load.</p>
 */
public final class MobAnimation {

    /** Which curve a key belongs to. */
    public enum Channel {
        ROTATION,
        POSITION
    }

    private final String name;
    private final float lengthSeconds;
    private final boolean loop;
    private final Map<String, Track> tracks;

    private MobAnimation(String name, float lengthSeconds, boolean loop, Map<String, Track> tracks) {
        this.name = name;
        this.lengthSeconds = lengthSeconds;
        this.loop = loop;
        this.tracks = Map.copyOf(tracks);
    }

    public String name() {
        return name;
    }

    public float lengthSeconds() {
        return lengthSeconds;
    }

    public boolean loop() {
        return loop;
    }

    /** True when any part has a curve at all. */
    public boolean hasTracks() {
        return !tracks.isEmpty();
    }

    /** The curves for one part, looked up by its authored or normalized name. */
    public Track track(String partName) {
        return partName == null ? null : tracks.get(normalizePart(partName));
    }

    /** Wraps a playhead into {@code [0, length)} for a looping clip, else clamps it. */
    public float wrap(float seconds) {
        if (lengthSeconds <= 0.0f) {
            return 0.0f;
        }
        if (!loop) {
            return Math.min(seconds, lengthSeconds);
        }
        float wrapped = seconds % lengthSeconds;
        return wrapped < 0.0f ? wrapped + lengthSeconds : wrapped;
    }

    /** The two curves one part can be driven by. */
    public static final class Track {

        private final Curve rotation;
        private final Curve position;

        private Track(Curve rotation, Curve position) {
            this.rotation = rotation;
            this.position = position;
        }

        /** Rotation curve in degrees (y-up model space), or {@code null}. */
        public Curve rotation() {
            return rotation;
        }

        /** Position curve in model pixels (y-up model space), or {@code null}. */
        public Curve position() {
            return position;
        }
    }

    /** Key times and values of one channel; values are reused, not allocated. */
    public static final class Curve {

        private final float[] times;
        private final Vector3f[] values;
        private final boolean step;

        private Curve(float[] times, Vector3f[] values, boolean step) {
            this.times = times;
            this.values = values;
            this.step = step;
        }

        public int keyCount() {
            return times.length;
        }

        /** Samples into {@code out}; copies a key when there are no neighbours. */
        public void sample(float time, Vector3f out) {
            int count = times.length;
            if (count == 0) {
                out.set(0.0f, 0.0f, 0.0f);
                return;
            }
            if (count == 1 || time <= times[0]) {
                out.set(values[0]);
                return;
            }
            if (time >= times[count - 1]) {
                out.set(values[count - 1]);
                return;
            }
            int next = 1;
            while (next < count && times[next] < time) {
                next++;
            }
            float from = times[next - 1];
            float to = times[next];
            float span = to - from;
            float blend = step || span <= 1.0e-6f ? 0.0f : (time - from) / span;
            out.set(values[next - 1]).lerp(values[next], blend);
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private float lengthSeconds = 1.0f;
        private boolean loop;
        private final List<Key> keys = new ArrayList<>();

        private Builder(String name) {
            String normalized = name == null ? "" : name.trim();
            this.name = normalized.isEmpty() ? "animation" : normalized;
        }

        /** Clip length in seconds (a looping clip wraps at this point). */
        public Builder lengthSeconds(float value) {
            if (value > 0.0f) {
                this.lengthSeconds = value;
            }
            return this;
        }

        public Builder loop(boolean value) {
            this.loop = value;
            return this;
        }

        public Builder key(String partName, Channel channel, float time,
                           float x, float y, float z, boolean step) {
            if (partName == null || channel == null) {
                return this;
            }
            String part = normalizePart(partName);
            if (part.isEmpty()) {
                return this;
            }
            keys.add(new Key(part, channel, Math.max(0.0f, time), x, y, z, step));
            return this;
        }

        public MobAnimation build() {
            Map<String, List<Key>> byPart = new LinkedHashMap<>();
            for (Key key : keys) {
                byPart.computeIfAbsent(key.part, name -> new ArrayList<>()).add(key);
            }
            Map<String, Track> tracks = new LinkedHashMap<>();
            for (Map.Entry<String, List<Key>> entry : byPart.entrySet()) {
                Curve rotation = curve(entry.getValue(), Channel.ROTATION);
                Curve position = curve(entry.getValue(), Channel.POSITION);
                if (rotation != null || position != null) {
                    tracks.put(entry.getKey(), new Track(rotation, position));
                }
            }
            return new MobAnimation(name, lengthSeconds, loop, tracks);
        }

        private static Curve curve(List<Key> keys, Channel channel) {
            List<Key> channelKeys = new ArrayList<>();
            for (Key key : keys) {
                if (key.channel == channel) {
                    channelKeys.add(key);
                }
            }
            if (channelKeys.isEmpty()) {
                return null;
            }
            channelKeys.sort((left, right) -> Float.compare(left.time, right.time));
            float[] times = new float[channelKeys.size()];
            Vector3f[] values = new Vector3f[channelKeys.size()];
            boolean step = true;
            for (int i = 0; i < channelKeys.size(); i++) {
                Key key = channelKeys.get(i);
                times[i] = key.time;
                values[i] = new Vector3f(key.x, key.y, key.z);
                step &= key.step;
            }
            return new Curve(times, values, step);
        }
    }

    private record Key(String part, Channel channel, float time, float x, float y, float z, boolean step) {
    }

    /** The same normalization {@link MobPart} names use, so clips and parts bind. */
    static String normalizePart(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        return out.toString();
    }
}
