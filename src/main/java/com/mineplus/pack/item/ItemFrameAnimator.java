package com.mineplus.pack.item;

import java.util.Objects;

/**
 * Plays one {@link ItemFrames} clip for one item holder: the piece of state
 * that turns a static frame list into an animation.
 *
 * <p>The animator owns no scheduler and no clock. Its owner (a weapon session,
 * a tool runtime, whatever holds the stack) calls {@link #tick()} once per
 * server tick and writes the returned state with {@link ItemFrameWriter}. That
 * keeps playback on the same clock as the gameplay that drives it — a reload
 * that takes 34 ticks plays its six frames across exactly those 34 ticks — and
 * lets the owner pause, retime or chain clips without a second timeline.</p>
 *
 * <p>A clip can be started with its declared duration or with an explicit one
 * (the gameplay action is the authority: a weapon whose reload is 34 ticks
 * passes 34 even if the model's clip says 30). One-shot clips report
 * {@link #finished()} on the tick after their last frame; a clip with a
 * {@code next} name chains into it instead. Looping clips never finish.</p>
 *
 * <p>Instances are cheap and reusable ({@link #start} resets playback), so one
 * animator per session is enough for an item's whole lifetime.</p>
 */
public final class ItemFrameAnimator {

    /** Safety ceiling for a chain of clips that name each other. */
    private static final int MAX_CHAIN = 64;

    private final ItemFrames frames;

    private String clipName;
    private int elapsed;
    private int duration;
    private boolean active;
    private boolean finished;
    private int currentState = -1;
    private String completedClip;

    public ItemFrameAnimator(ItemFrames frames) {
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    /** The frame spec this animator plays. */
    public ItemFrames frames() {
        return frames;
    }

    /**
     * Starts a clip with its declared duration (or one tick per frame when the
     * clip declares no duration).
     *
     * @return {@code false} when the clip does not exist
     */
    public boolean start(String clipName) {
        ItemFrames.Clip clip = frames.clip(clipName);
        if (clip == null) {
            return false;
        }
        return startClip(clip, clip.ticks());
    }

    /**
     * Starts a clip retimed to {@code ticks} — the gameplay action's duration,
     * which usually differs from the model's authored duration.
     *
     * @return {@code false} when the clip does not exist
     */
    public boolean start(String clipName, int ticks) {
        ItemFrames.Clip clip = frames.clip(clipName);
        if (clip == null) {
            return false;
        }
        return startClip(clip, ticks);
    }

    private boolean startClip(ItemFrames.Clip clip, int ticks) {
        this.clipName = clip.name();
        this.elapsed = 0;
        this.duration = ticks > 0 ? ticks : clip.frameCount();
        this.active = true;
        this.finished = false;
        this.completedClip = null;
        this.currentState = frameState(clip.frameCount(), clip.loop());
        return true;
    }

    /**
     * Advances playback by one tick and returns the state to write, or
     * {@code -1} when nothing is playing.
     *
     * <p>A one-shot clip ticks through its frames, marks itself
     * {@link #finished()} at the end (or chains into its {@code next} clip),
     * and keeps returning its final state so the caller's stack never flickers
     * back to idle before it decides what comes next. The state stays valid
     * until the caller writes something else.</p>
     */
    public int tick() {
        if (!active) {
            return -1;
        }
        for (int hop = 0; hop < MAX_CHAIN; hop++) {
            ItemFrames.Clip clip = frames.clip(clipName);
            if (clip == null) {
                break;
            }
            if (clip.loop()) {
                elapsed = Math.floorMod(elapsed + 1, Math.max(1, duration));
                currentState = frameState(clip.frameCount(), true);
                return currentState;
            }
            elapsed++;
            if (elapsed < duration) {
                currentState = frameState(clip.frameCount(), false);
                return currentState;
            }
            if (clip.next() != null && frames.clip(clip.next()) != null) {
                startClip(frames.clip(clip.next()), frames.clip(clip.next()).ticks());
                return currentState;
            }
            active = false;
            finished = true;
            completedClip = clip.name();
            elapsed = duration;
            currentState = frames.stateAtFrame(clip.name(), clip.frameCount() - 1);
            return currentState;
        }
        active = false;
        finished = true;
        return currentState;
    }

    /** The state playback currently shows, without advancing. */
    public int state() {
        return currentState;
    }

    /** Stops playback immediately; the caller owns the visual from here. */
    public void stop() {
        active = false;
        finished = false;
        clipName = null;
        elapsed = 0;
        duration = 0;
        currentState = -1;
        completedClip = null;
    }

    /** True while a clip is playing. */
    public boolean active() {
        return active;
    }

    /**
     * True once a one-shot clip has consumed its last frame. The flag survives
     * until another clip starts or {@link #stop()} runs, so a caller that ticks
     * on a slower schedule cannot miss it.
     */
    public boolean finished() {
        return finished;
    }

    /** The clip that just completed (the chained-to clip, if one ran), or null. */
    public String completedClip() {
        return completedClip;
    }

    /** The clip currently playing (or last played), or null. */
    public String clipName() {
        return clipName;
    }

    /** Ticks elapsed in the current clip. */
    public int elapsedTicks() {
        return elapsed;
    }

    /** Whole duration of the current clip in ticks. */
    public int durationTicks() {
        return duration;
    }

    /** Playback progress in {@code [0,1]}. */
    public float progress() {
        if (duration <= 0) {
            return finished ? 1.0f : 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) elapsed / duration));
    }

    /** True when this animator is playing the named clip right now. */
    public boolean playing(String name) {
        return active && name != null && name.equalsIgnoreCase(clipName);
    }

    private int frameState(int frameCount, boolean loop) {
        int total = duration > 0 ? duration : frameCount;
        int index = total <= 0 ? 0 : (int) ((long) elapsed * frameCount / total);
        if (loop) {
            index = Math.floorMod(index, frameCount);
        } else {
            index = Math.min(index, frameCount - 1);
        }
        int state = frames.stateAtFrame(clipName, index);
        return state < 0 ? 0 : state;
    }
}
