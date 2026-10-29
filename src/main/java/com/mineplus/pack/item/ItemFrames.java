package com.mineplus.pack.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The frame choreography of one framed item — the declarative version of
 * "this item renders different models per state, frame by frame".
 *
 * <p>A framed item is built from verbatim model JSONs the module registers as
 * raw pack assets (never virtual models). One model is the resting state
 * (idle); optionally one model is the loaded state, one is the use pose, and
 * any number of <b>named clips</b> play across time (a reload, scope-in,
 * scope-hold, scope-out, equip, action cycle — anything the item's gameplay
 * needs). A clip is a list of model references plus its own duration and loop
 * flag, so content is not limited to the four legacy poses.</p>
 *
 * <p><b>State number space.</b> The client renders from a single integer the
 * module writes onto the stack ({@link ItemFrameWriter}), and the pack
 * serializes this spec as a {@code range_dispatch} over the stack's
 * {@code custom_model_data} with the idle model as the fallback:</p>
 * <ul>
 *   <li>{@code 0} — resting/idle: also the effective value when the stack
 *       carries no custom-model-data at all (pristine items render idle).</li>
 *   <li>{@code 1..N} — the legacy reload models, in play order.</li>
 *   <li>{@code N+1} — the loaded state, when a loaded model exists.</li>
 *   <li>{@code N+2} — the use pose, when a use model exists.</li>
 *   <li>{@code N+3..} — any model a named clip references for the first time,
 *       in clip declaration order.</li>
 * </ul>
 *
 * <p>The legacy allocation is preserved exactly, so stacks written by an older
 * version keep rendering the pose they were written with. New clip models are
 * appended after it. Every state is a distinct model, so one integer selects
 * exactly one pose.</p>
 *
 * <p>Usage: build one immutable instance per item, hand it to
 * {@link PackItemDefinition.Builder#frames(ItemFrames)} at registration, and
 * drive it with {@link ItemFrameAnimator} (or {@link ItemFrameWriter} plus the
 * state helpers here) at runtime.</p>
 */
public final class ItemFrames {

    /**
     * One named animation: its frames in play order, how long one full pass
     * lasts ({@code 0} = one tick per frame), whether it repeats, and an
     * optional clip to chain into when it finishes.
     *
     * <p>{@code ticks} is the whole clip duration, not the per-frame duration:
     * a six-frame reload over 34 ticks steps a frame roughly every 5-6 ticks,
     * and the sampler distributes the frames evenly across the span.</p>
     */
    public record Clip(String name, List<String> references, int ticks, boolean loop, String next) {

        public Clip {
            name = normalizeName(name);
            List<String> frames = new ArrayList<>();
            if (references != null) {
                for (String reference : references) {
                    frames.add(requireReference(reference, "clip '" + name + "'"));
                }
            }
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("Clip '" + name + "' has no frames");
            }
            references = List.copyOf(frames);
            ticks = Math.max(0, ticks);
            next = next == null || next.isBlank() ? null : normalizeName(next);
        }

        /** A one-shot clip that returns to the resting pose. */
        public static Clip once(String name, List<String> references, int ticks) {
            return new Clip(name, references, ticks, false, null);
        }

        /** A repeating clip (a scope hold, a spinning part). */
        public static Clip loop(String name, List<String> references, int ticks) {
            return new Clip(name, references, ticks, true, null);
        }

        public int frameCount() {
            return references.size();
        }
    }

    /**
     * One clip a client can play on its own clock.
     *
     * <p>A server tick is too coarse a clock for a fast hand animation: writing
     * one model per tick caps the pose at 20 Hz and costs one stack rewrite per
     * frame — and every rewrite of a held stack replays the client's equip
     * bob. The pack therefore serializes a sequence as a nested dispatch over
     * the client's own {@code minecraft:cooldown} property, and the runtime
     * only writes the {@link #gateState()} once and starts a cooldown of the
     * clip's length ({@link org.bukkit.entity.Player#setCooldown}); the client
     * then selects a frame every rendered frame with no further packets.</p>
     *
     * <p>{@code gateState} is the stack state whose dispatch entry owns the
     * sequence. It is normally the first frame's own state, so a server that
     * writes states per frame (the legacy representation, or a modern client
     * the plan cannot help) still renders the exact same poses.
     * {@code fallbackReference} is the model shown when the cooldown is not
     * running — the pose the item settles into.</p>
     */
    public record Sequence(String name, int gateState, List<String> frames, String fallbackReference) {

        public Sequence {
            name = normalizeName(name);
            frames = frames == null ? List.of() : List.copyOf(frames);
            fallbackReference = fallbackReference == null
                    ? null
                    : requireReference(fallbackReference, "sequence '" + name + "' fallback");
        }

        /** True when this sequence has something to animate. */
        public boolean animates() {
            return gateState >= 0 && !frames.isEmpty();
        }

        /**
         * A one-frame sequence holds that pose for the whole cooldown: its
         * entry threshold is a hair above zero so the fallback still wins once
         * the cooldown has fully elapsed.
         */
        public boolean holdsSingleFrame() {
            return frames.size() == 1;
        }
    }

    /**
     * Every client-clock sequence of this spec, keyed by name ({@code reload},
     * {@code shot}, {@code equip}, {@code cycle}, {@code scope_in},
     * {@code scope_out}). Only sequences with a usable gate are present.
     */
    public record ClipPlan(List<Sequence> sequences) {

        public static final ClipPlan NONE = new ClipPlan(List.of());

        public ClipPlan {
            sequences = sequences == null ? List.of() : List.copyOf(sequences);
        }

        /** The sequence with this name, or {@code null}. */
        public Sequence sequence(String name) {
            if (name == null) {
                return null;
            }
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (Sequence sequence : sequences) {
                if (sequence.name().equals(key)) {
                    return sequence;
                }
            }
            return null;
        }

        public Sequence reload() {
            return sequence("reload");
        }

        public Sequence shot() {
            return sequence("fire");
        }

        public Sequence equip() {
            return sequence("equip");
        }

        public Sequence cycle() {
            return sequence("cycle");
        }

        public Sequence scopeIn() {
            return sequence("scope_in");
        }

        public Sequence scopeOut() {
            return sequence("scope_out");
        }

        public boolean isEmpty() {
            return sequences.isEmpty();
        }
    }

    private final String idleReference;
    private final String loadedReference;
    private final String fireReference;
    private final List<String> reloadReferences;
    private final List<Clip> clips;
    private final Map<String, Clip> clipsByName;
    private final Map<String, Integer> states;
    private final Map<Integer, String> models;
    private final List<PackItemDefinition.Frame> dispatch;
    private final ClipPlan plan;

    private ItemFrames(String idleReference, String loadedReference, String fireReference,
                       List<String> reloadReferences, List<Clip> namedClips) {
        this.idleReference = requireReference(idleReference, "idle");
        this.loadedReference = loadedReference == null || loadedReference.isBlank()
                ? null
                : requireReference(loadedReference, "loaded");
        this.fireReference = fireReference == null || fireReference.isBlank()
                ? null
                : requireReference(fireReference, "fire");
        List<String> reload = new ArrayList<>();
        if (reloadReferences != null) {
            for (String reference : reloadReferences) {
                reload.add(requireReference(reference, "reload"));
            }
        }
        this.reloadReferences = List.copyOf(reload);

        // Named clips, plus the legacy reload list as a clip under its historic
        // name when the caller did not declare one explicitly.
        List<Clip> resolved = new ArrayList<>();
        Map<String, Clip> byName = new LinkedHashMap<>();
        if (namedClips != null) {
            for (Clip clip : namedClips) {
                if (clip == null || byName.containsKey(clip.name())) {
                    continue;
                }
                resolved.add(clip);
                byName.put(clip.name(), clip);
            }
        }
        if (!reload.isEmpty() && !byName.containsKey("reload")) {
            Clip reloadClip = new Clip("reload", reload, 0, false, null);
            resolved.add(reloadClip);
            byName.put("reload", reloadClip);
        }
        this.clips = List.copyOf(resolved);
        this.clipsByName = Map.copyOf(byName);

        // State allocation: idle first, then the legacy reload/loaded/fire
        // order (so old written values keep their meaning), then every new
        // clip reference in declaration order.
        Map<String, Integer> allocation = new LinkedHashMap<>();
        Map<Integer, String> reverse = new LinkedHashMap<>();
        allocate(allocation, reverse, idleReference);
        for (String reference : reload) {
            allocate(allocation, reverse, reference);
        }
        if (loadedReference != null) {
            allocate(allocation, reverse, this.loadedReference);
        }
        if (fireReference != null) {
            allocate(allocation, reverse, this.fireReference);
        }
        for (Clip clip : clips) {
            for (String reference : clip.references()) {
                allocate(allocation, reverse, reference);
            }
        }
        this.states = Map.copyOf(allocation);
        this.models = Map.copyOf(reverse);

        List<PackItemDefinition.Frame> entries = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : reverse.entrySet()) {
            if (entry.getKey() > 0) {
                entries.add(new PackItemDefinition.Frame(entry.getKey(), entry.getValue()));
            }
        }
        this.dispatch = List.copyOf(entries);

        // Client-clock sequences. Every sequence is gated on a state the
        // runtime writes once; a state carries at most one sequence, so the
        // legacy per-frame writes (and a client that ignores the plan) render
        // exactly the same poses in the same order.
        String restReference = this.loadedReference != null ? this.loadedReference : idleReference;
        List<Sequence> planSequences = new ArrayList<>();
        Set<Integer> gatedStates = new LinkedHashSet<>();

        Clip declaredReload = clipsByName.get("reload");
        List<String> reloadFrames = declaredReload != null ? declaredReload.references() : reload;
        if (!reloadFrames.isEmpty()) {
            int gate = stateOf(reloadFrames.get(0));
            if (gate >= 0 && gatedStates.add(gate)) {
                planSequences.add(new Sequence("reload", gate, reloadFrames, restReference));
            }
        }

        // A shot is gated on the resting state: the gun is already in it when
        // it fires, so starting the sequence costs no stack rewrite, and the
        // fallback is where the recoil settles.
        Clip declaredFire = clipsByName.get("fire");
        List<String> shotFrames = declaredFire != null
                ? declaredFire.references()
                : (this.fireReference == null ? List.of() : List.of(this.fireReference));
        if (!shotFrames.isEmpty()) {
            int gate = stateOf(restReference);
            boolean onlyRest = shotFrames.size() == 1 && restReference.equals(shotFrames.get(0));
            if (!onlyRest && gate >= 0 && gatedStates.add(gate)) {
                String name = declaredFire != null ? declaredFire.name() : "fire";
                planSequences.add(new Sequence(name, gate, shotFrames, restReference));
            }
        }

        addClipSequence(planSequences, gatedStates, "equip", restReference);
        addClipSequence(planSequences, gatedStates, "cycle", restReference);
        String holdReference = firstReferenceOf("scope_hold");
        addClipSequence(planSequences, gatedStates, "scope_in",
                holdReference != null ? holdReference : restReference);
        addClipSequence(planSequences, gatedStates, "scope_out", restReference);
        this.plan = new ClipPlan(planSequences);
    }

    private void addClipSequence(List<Sequence> out, Set<Integer> gated, String name, String fallback) {
        Clip clip = clipsByName.get(name);
        if (clip == null || clip.frameCount() < 2) {
            return;
        }
        int gate = stateOf(clip.references().get(0));
        if (gate < 0 || !gated.add(gate)) {
            return;
        }
        out.add(new Sequence(name, gate, clip.references(), fallback));
    }

    private String firstReferenceOf(String clipName) {
        Clip clip = clipsByName.get(clipName);
        return clip == null ? null : clip.references().get(0);
    }

    private static void allocate(Map<String, Integer> allocation, Map<Integer, String> reverse,
                                 String reference) {
        if (reference == null || allocation.containsKey(reference)) {
            return;
        }
        int state = allocation.size();
        allocation.put(reference, state);
        reverse.put(state, reference);
    }

    private static String requireReference(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank " + label + " model reference");
        }
        return normalized;
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid clip name: '" + value + "'");
        }
        return normalized;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * A plain raw-model item: one model, no states. Registering this is the
     * simplest way to put an imported model JSON on an item.
     */
    public static ItemFrames of(String idleReference) {
        return new ItemFrames(idleReference, null, null, List.of(), List.of());
    }

    /**
     * A framed item with the legacy three states. {@code loadedReference} is
     * optional (null = the item has no distinct loaded visual); the animation
     * models play in order as state runs from 1 to {@code N}.
     */
    public static ItemFrames with(String idleReference, String loadedReference, List<String> reloadReferences) {
        return new ItemFrames(idleReference, loadedReference, null, reloadReferences, List.of());
    }

    /** A framed item with a use pose in addition to the legacy states. */
    public static ItemFrames withFire(String idleReference, String loadedReference,
                                      List<String> reloadReferences, String fireReference) {
        return new ItemFrames(idleReference, loadedReference, fireReference, reloadReferences, List.of());
    }

    /** A builder for arbitrary named clips. */
    public static Builder builder(String idleReference) {
        return new Builder(idleReference);
    }

    public static final class Builder {
        private final String idleReference;
        private String loadedReference;
        private String fireReference;
        private final List<String> reloadReferences = new ArrayList<>();
        private final List<Clip> clips = new ArrayList<>();

        private Builder(String idleReference) {
            this.idleReference = idleReference;
        }

        public Builder loaded(String reference) {
            this.loadedReference = reference;
            return this;
        }

        public Builder fire(String reference) {
            this.fireReference = reference;
            return this;
        }

        /** The legacy reload sequence; also exposed as the {@code reload} clip. */
        public Builder reload(List<String> references) {
            reloadReferences.clear();
            if (references != null) {
                reloadReferences.addAll(references);
            }
            return this;
        }

        public Builder clip(Clip clip) {
            if (clip != null) {
                clips.add(clip);
            }
            return this;
        }

        public Builder clip(String name, List<String> references, int ticks, boolean loop) {
            return clip(new Clip(name, references, ticks, loop, null));
        }

        public ItemFrames build() {
            return new ItemFrames(idleReference, loadedReference, fireReference, reloadReferences, clips);
        }
    }

    // ------------------------------------------------------------------
    // References and legacy state accessors
    // ------------------------------------------------------------------

    /** The resting-state model reference; also the dispatch fallback. */
    public String idleReference() {
        return idleReference;
    }

    /** The loaded-state model reference, or {@code null} when the item has none. */
    public String loadedReference() {
        return loadedReference;
    }

    /** The use-pose model reference, or {@code null} when the item has none. */
    public String fireReference() {
        return fireReference;
    }

    /** The legacy reload models, in play order; empty when the item has none. */
    public List<String> reloadReferences() {
        return reloadReferences;
    }

    /** Every named clip, in declaration order (the legacy reload included). */
    public List<Clip> clips() {
        return clips;
    }

    /** The clip with this name, or {@code null}. Names are lower-case. */
    public Clip clip(String name) {
        if (name == null) {
            return null;
        }
        return clipsByName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** True when the item has any state beyond the resting model. */
    public boolean isFramed() {
        return loadedReference != null || fireReference != null || !reloadReferences.isEmpty() || !clips.isEmpty();
    }

    /** True when the item declares a distinct use pose. */
    public boolean hasFireModel() {
        return fireReference != null;
    }

    /** True when the item declares a clip with this name. */
    public boolean hasClip(String name) {
        return clip(name) != null;
    }

    /** The state number for the resting visual; always {@code 0}. */
    public int idleState() {
        return 0;
    }

    /** The state number for the loaded visual, or {@code 0} when it has none. */
    public int loadedState() {
        return loadedReference == null ? 0 : stateOf(loadedReference);
    }

    /** The state number for the use pose (loaded when it has no fire model). */
    public int fireState() {
        if (fireReference == null) {
            return loadedState();
        }
        return stateOf(fireReference);
    }

    /**
     * The client-clock sequences of this spec: the reload, the shot recoil,
     * the equip pose and every other multi-frame clip, each behind the state
     * the runtime writes to start it. Empty when nothing can run on the
     * client's clock (single-pose items).
     */
    public ClipPlan plan() {
        return plan;
    }

    /** The state number of one model reference, or {@code -1} when unknown. */
    public int stateOf(String reference) {
        if (reference == null) {
            return -1;
        }
        Integer state = states.get(reference.trim().toLowerCase(Locale.ROOT));
        return state == null ? -1 : state;
    }

    /** The model reference allocated to a state number, or {@code null}. */
    public String referenceOf(int state) {
        return models.get(state);
    }

    /** Every state number this spec can render, ascending (excluding idle). */
    public List<Integer> states() {
        return List.copyOf(models.keySet());
    }

    /**
     * The animation state for a progress value in {@code [0,1]} over the
     * legacy reload models. {@code 0} while the progress has not left zero;
     * the caller decides what the completed visual is (usually the loaded
     * state).
     */
    public int reloadState(float progress) {
        if (reloadReferences.isEmpty()) {
            return 0;
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        if (clamped <= 0.0f) {
            return 0;
        }
        int index = (int) Math.floor(clamped * reloadReferences.size());
        index = Math.min(index, reloadReferences.size() - 1);
        return stateOf(reloadReferences.get(index));
    }

    /**
     * The state a clip renders at {@code progress} in {@code [0,1]}. Frames
     * are distributed evenly; the last frame is held once progress reaches
     * {@code 1}. Returns {@code -1} for an unknown or empty clip.
     */
    public int stateAtProgress(String clipName, float progress) {
        Clip clip = clip(clipName);
        if (clip == null) {
            return -1;
        }
        int frames = clip.frameCount();
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        int index = (int) Math.floor(clamped * frames);
        index = Math.min(index, frames - 1);
        return stateOf(clip.references().get(index));
    }

    /**
     * The state a clip renders after {@code elapsedTicks} of its declared
     * duration. A clip with no declared duration spends one tick per frame.
     * Looping clips wrap around. Returns {@code -1} for an unknown clip.
     */
    public int stateAtTick(String clipName, int elapsedTicks) {
        Clip clip = clip(clipName);
        if (clip == null) {
            return -1;
        }
        int frames = clip.frameCount();
        int total = clip.ticks() > 0 ? clip.ticks() : frames;
        int elapsed = Math.max(0, elapsedTicks);
        int index = (int) ((long) elapsed * frames / Math.max(1, total));
        index = clip.loop() ? Math.floorMod(index, frames) : Math.min(index, frames - 1);
        return stateOf(clip.references().get(index));
    }

    /** The state of one frame index of a clip, wrapping when it loops. */
    public int stateAtFrame(String clipName, int frameIndex) {
        Clip clip = clip(clipName);
        if (clip == null) {
            return -1;
        }
        int frames = clip.frameCount();
        int index = clip.loop() ? Math.floorMod(frameIndex, frames) : Math.max(0, Math.min(frameIndex, frames - 1));
        return stateOf(clip.references().get(index));
    }

    /** All state-relevant references (idle, loaded, fire, animation), for diagnostics. */
    public List<String> references() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.add(idleReference);
        if (loadedReference != null) {
            all.add(loadedReference);
        }
        if (fireReference != null) {
            all.add(fireReference);
        }
        all.addAll(reloadReferences);
        for (Clip clip : clips) {
            all.addAll(clip.references());
        }
        return List.copyOf(all);
    }

    /**
     * The dispatch entries this spec serializes into, ascending by threshold.
     * Empty for a single-model item. Package-private wiring for
     * {@link PackItemDefinition}.
     */
    List<PackItemDefinition.Frame> dispatch() {
        return dispatch;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemFrames frames)) {
            return false;
        }
        return idleReference.equals(frames.idleReference)
                && Objects.equals(loadedReference, frames.loadedReference)
                && Objects.equals(fireReference, frames.fireReference)
                && reloadReferences.equals(frames.reloadReferences)
                && clips.equals(frames.clips);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idleReference, loadedReference, fireReference, reloadReferences, clips);
    }

    @Override
    public String toString() {
        return (isFramed() ? "frames" : "model") + "[" + idleReference
                + (loadedReference != null ? ", loaded=" + loadedReference : "")
                + (fireReference != null ? ", fire=" + fireReference : "")
                + (reloadReferences.isEmpty() ? "" : ", reload=" + reloadReferences.size())
                + (clips.isEmpty() ? "" : ", clips=" + clips.stream().map(Clip::name).toList())
                + "]";
    }
}
