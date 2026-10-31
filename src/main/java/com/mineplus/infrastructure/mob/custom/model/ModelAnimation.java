package com.mineplus.infrastructure.mob.custom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One authored animation clip: per-bone rotation/position/scale curves.
 *
 * <p>Units are the model's own — rotation in degrees (y-up Blockbench space),
 * position in model pixels, scale as a multiplier. Curves are sampled per
 * <em>key</em>: each key carries its own interpolation (linear, step,
 * catmullrom), which is how Blockbench authors them. Unparsable Molang values
 * become {@code 0} with a recorded issue, so a clip authored with expressions
 * still plays its numeric keys.</p>
 */
public final class ModelAnimation {

    public enum LoopMode {
        /** Play once and clamp at the last key. */
        ONCE,
        /** Wrap to the start when the clip length is reached. */
        LOOP,
        /** Play once and hold the last pose until replaced. */
        HOLD
    }

    public enum Interpolation {
        LINEAR,
        STEP,
        CATMULLROM
    }

    /** One channel of one bone. */
    public static final class Curve {

        private final float[] times;
        private final float[][] values;
        private final Interpolation[] interpolations;

        public Curve(float[] times, float[][] values, Interpolation[] interpolations) {
            this.times = times;
            this.values = values;
            this.interpolations = interpolations;
        }

        public int keyCount() {
            return times.length;
        }

        /**
         * Samples into {@code out[0..2]}; copies a key when there is no neighbour.
         * Catmullrom uses the two keys on each side of the segment (clamped).
         */
        public void sample(float time, float[] out) {
            int count = times.length;
            if (count == 0) {
                out[0] = out[1] = out[2] = 0.0f;
                return;
            }
            if (count == 1 || time <= times[0]) {
                System.arraycopy(values[0], 0, out, 0, 3);
                return;
            }
            if (time >= times[count - 1]) {
                System.arraycopy(values[count - 1], 0, out, 0, 3);
                return;
            }
            int next = 1;
            while (next < count && times[next] < time) {
                next++;
            }
            int prev = next - 1;
            float span = times[next] - times[prev];
            float blend = span <= 1.0e-6f ? 0.0f : (time - times[prev]) / span;
            switch (interpolations[prev]) {
                case STEP -> System.arraycopy(values[prev], 0, out, 0, 3);
                case CATMULLROM -> catmullrom(prev, next, blend, out);
                default -> {
                    for (int c = 0; c < 3; c++) {
                        out[c] = values[prev][c] + (values[next][c] - values[prev][c]) * blend;
                    }
                }
            }
        }

        private void catmullrom(int prev, int next, float blend, float[] out) {
            int p0 = Math.max(0, prev - 1);
            int p3 = Math.min(times.length - 1, next + 1);
            for (int c = 0; c < 3; c++) {
                float v0 = values[p0][c];
                float v1 = values[prev][c];
                float v2 = values[next][c];
                float v3 = values[p3][c];
                float b2 = blend * blend;
                float b3 = b2 * blend;
                out[c] = 0.5f * ((2.0f * v1)
                        + (-v0 + v2) * blend
                        + (2.0f * v0 - 5.0f * v1 + 4.0f * v2 - v3) * b2
                        + (-v0 + 3.0f * v1 - 3.0f * v2 + v3) * b3);
            }
        }
    }

    /** The curves one bone can be driven by; any of them may be {@code null}. */
    public record BoneTrack(Curve rotation, Curve position, Curve scale) {
    }

    private final String name;
    private final String rawName;
    private final float lengthSeconds;
    private final LoopMode loopMode;
    private final Map<String, BoneTrack> tracks;

    public ModelAnimation(String name, String rawName, float lengthSeconds, LoopMode loopMode,
                          Map<String, BoneTrack> tracks) {
        this.name = name;
        this.rawName = rawName == null ? name : rawName;
        this.lengthSeconds = Math.max(0.0f, lengthSeconds);
        this.loopMode = loopMode == null ? LoopMode.ONCE : loopMode;
        this.tracks = Map.copyOf(tracks);
    }

    /** Lower-case clip name used for state binding. */
    public String name() {
        return name;
    }

    public String rawName() {
        return rawName;
    }

    public float lengthSeconds() {
        return lengthSeconds;
    }

    public LoopMode loopMode() {
        return loopMode;
    }

    /** The curves of one bone, keyed by the same normalized names {@link ModelBone} uses. */
    public BoneTrack track(String boneName) {
        return boneName == null ? null : tracks.get(boneName);
    }

    /** Every driven bone's curves, for samplers. */
    public Map<String, BoneTrack> trackEntries() {
        return tracks;
    }

    /** Wraps a playhead into {@code [0, length)} for a looping clip, else clamps it. */
    public float wrap(float seconds) {
        if (lengthSeconds <= 0.0f) {
            return 0.0f;
        }
        if (loopMode != LoopMode.LOOP) {
            return Math.min(seconds, lengthSeconds);
        }
        float wrapped = seconds % lengthSeconds;
        return wrapped < 0.0f ? wrapped + lengthSeconds : wrapped;
    }

    // ------------------------------------------------------------------
    // Builder (parsers use it; keys may arrive out of order and per-channel)
    // ------------------------------------------------------------------

    public static Builder builder(String rawName) {
        return new Builder(rawName);
    }

    public static final class Builder {

        private final String rawName;
        private float lengthSeconds;
        private LoopMode loopMode = LoopMode.ONCE;
        private final Map<String, Map<String, List<Key>>> keys = new LinkedHashMap<>();

        private record Key(float time, float x, float y, float z, Interpolation interpolation) {
        }

        public Builder(String rawName) {
            this.rawName = rawName == null ? "animation" : rawName;
        }

        public Builder lengthSeconds(float value) {
            if (value > 0.0f) {
                this.lengthSeconds = value;
            }
            return this;
        }

        public Builder loopMode(LoopMode mode) {
            if (mode != null) {
                this.loopMode = mode;
            }
            return this;
        }

        public Builder key(String boneName, String channel, float time,
                           float x, float y, float z, Interpolation interpolation) {
            if (boneName == null || channel == null) {
                return this;
            }
            keys.computeIfAbsent(boneName.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>())
                    .computeIfAbsent(channel.toLowerCase(Locale.ROOT), c -> new ArrayList<>())
                    .add(new Key(Math.max(0.0f, time), x, y, z,
                            interpolation == null ? Interpolation.LINEAR : interpolation));
            return this;
        }

        public ModelAnimation build() {
            Map<String, BoneTrack> tracks = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, List<Key>>> boneEntry : keys.entrySet()) {
                Curve rotation = curve(boneEntry.getValue().get("rotation"));
                Curve position = curve(boneEntry.getValue().get("position"));
                Curve scale = curve(boneEntry.getValue().get("scale"));
                if (rotation != null || position != null || scale != null) {
                    tracks.put(boneEntry.getKey(), new BoneTrack(rotation, position, scale));
                }
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            return new ModelAnimation(name, rawName, lengthSeconds, loopMode, tracks);
        }

        private static Curve curve(List<Key> channelKeys) {
            if (channelKeys == null || channelKeys.isEmpty()) {
                return null;
            }
            List<Key> sorted = new ArrayList<>(channelKeys);
            sorted.sort((left, right) -> Float.compare(left.time, right.time));
            int count = sorted.size();
            float[] times = new float[count];
            float[][] values = new float[count][3];
            Interpolation[] interpolations = new Interpolation[count];
            for (int i = 0; i < count; i++) {
                Key key = sorted.get(i);
                times[i] = key.time;
                values[i][0] = key.x;
                values[i][1] = key.y;
                values[i][2] = key.z;
                interpolations[i] = key.interpolation;
            }
            return new Curve(times, values, interpolations);
        }
    }
}
