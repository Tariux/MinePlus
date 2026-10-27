package com.mineplus.pack.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The frame choreography of one framed item — the declarative version of
 * "this item renders different models per state, frame by frame".
 *
 * <p>A framed item is built from verbatim model JSONs the module registers as
 * raw pack assets (never virtual models). One model is the resting state
 * (idle); optionally one model is the loaded state (round chambered, magazine
 * seated — whatever the item's semantics are); and optionally a list of
 * models plays as an animation across a progress value (a reload, a charge,
 * a draw).</p>
 *
 * <p><b>State number space.</b> The client renders from a single integer the
 * module writes onto the stack ({@link ItemFrameWriter}), and the pack
 * serializes this spec as a {@code range_dispatch} over the stack's
 * {@code custom_model_data} with the idle model as the fallback:</p>
 * <ul>
 *   <li>{@code 0} — resting/idle: also the effective value when the stack
 *       carries no custom-model-data at all (pristine items render idle).</li>
 *   <li>{@code 1..N} — the {@code N} animation models, in play order: state
 *       {@code v} shows animation model {@code v-1}.</li>
 *   <li>{@code N+1} — the loaded state, when a loaded model exists.</li>
 *   <li>{@code N+2} — the fire state, when a fire model exists (the pose the
 *       item shows while it is being used: a gun kicking, a tool striking).</li>
 * </ul>
 *
 * <p>The states are allocated in that order and every one of them is a
 * distinct model, so the single integer selects exactly one pose. The fire
 * state sits <em>above</em> the loaded state because the dispatch picks the
 * last entry whose threshold the stack value clears: the fire pose must win
 * over the loaded pose while it lasts, and the loaded pose must win again as
 * soon as the stack value drops back.</p>
 *
 * <p>One integer write switches the whole visual — no per-frame items, no
 * client tricks, strictly additive to vanilla. A single-model item
 * ({@link #of}) has no dispatch at all; its raw model renders as-is.</p>
 *
 * <p>Usage: build one immutable instance per item, hand it to
 * {@link PackItemDefinition.Builder#frames(ItemFrames)} at registration, and
 * drive it with {@link ItemFrameWriter} at runtime — e.g.
 * {@code writer.apply(stack, frames.reloadState(progress))} for the
 * animation and {@code frames.loadedState()} / {@code #idleState()} for the
 * resting states, {@code frames.fireState()} for the use pose.</p>
 */
public final class ItemFrames {

    private final String idleReference;
    private final String loadedReference;
    private final String fireReference;
    private final List<String> reloadReferences;

    private ItemFrames(String idleReference, String loadedReference, String fireReference,
                       List<String> reloadReferences) {
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
    }

    private static String requireReference(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank " + label + " model reference");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A plain raw-model item: one model, no states. Registering this is the
     * simplest way to put an imported model JSON on an item.
     */
    public static ItemFrames of(String idleReference) {
        return new ItemFrames(idleReference, null, null, List.of());
    }

    /**
     * A full framed item. {@code loadedReference} is optional (null = the item
     * has no distinct loaded visual); the animation models play in order as
     * the state runs from 1 to {@code N}.
     */
    public static ItemFrames with(String idleReference, String loadedReference, List<String> reloadReferences) {
        return new ItemFrames(idleReference, loadedReference, null, reloadReferences);
    }

    /**
     * A framed item with a use pose: the model shown while the item is being
     * used (a gun kicking, a tool striking). The fire state is allocated above
     * the loaded state, so writing it always wins over the loaded pose and
     * dropping back to the loaded state restores it.
     */
    public static ItemFrames withFire(String idleReference, String loadedReference,
                                      List<String> reloadReferences, String fireReference) {
        return new ItemFrames(idleReference, loadedReference, fireReference, reloadReferences);
    }

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

    /** The animation models, in play order; empty when the item has none. */
    public List<String> reloadReferences() {
        return reloadReferences;
    }

    /** True when the item has any state beyond the resting model. */
    public boolean isFramed() {
        return loadedReference != null || fireReference != null || !reloadReferences.isEmpty();
    }

    /** True when the item declares a distinct use pose. */
    public boolean hasFireModel() {
        return fireReference != null;
    }

    /** The state number for the resting visual; always {@code 0}. */
    public int idleState() {
        return 0;
    }

    /**
     * The state number for the loaded visual: {@code N+1} when a loaded model
     * exists, otherwise {@code 0} (fall back to idle).
     */
    public int loadedState() {
        return loadedReference != null ? reloadReferences.size() + 1 : 0;
    }

    /**
     * The state number for the use pose: {@code N+2} when the item has both a
     * loaded and a fire model, {@code N+1} when it has only a fire model.
     * An item without a fire model keeps its loaded pose while it is used.
     */
    public int fireState() {
        if (fireReference == null) {
            return loadedState();
        }
        return reloadReferences.size() + (loadedReference != null ? 2 : 1);
    }

    /**
     * The animation state for a progress value in {@code [0,1]} — an even walk
     * through the animation models, ending on the last one. {@code 0} is
     * returned while the progress has not reached the first model yet; the
     * caller decides what the completed visual is (typically
     * {@link #loadedState()}).
     *
     * <p>The walk covers every animation model exactly once: a progress of
     * {@code 0.5} over five models shows the third one, and a progress of
     * {@code 1} shows the fifth. The first model is reached as soon as the
     * progress leaves zero, so an animation never skips its opening pose.</p>
     */
    public int reloadState(float progress) {
        int frames = reloadReferences.size();
        if (frames == 0) {
            return 0;
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        if (clamped <= 0.0f) {
            return 0;
        }
        int index = (int) Math.floor(clamped * frames);
        if (index >= frames) {
            index = frames - 1;
        }
        return index + 1;
    }

    /** All state-relevant references (idle, loaded, fire, animation), for diagnostics. */
    public List<String> references() {
        List<String> all = new ArrayList<>();
        all.add(idleReference);
        if (loadedReference != null) {
            all.add(loadedReference);
        }
        if (fireReference != null) {
            all.add(fireReference);
        }
        all.addAll(reloadReferences);
        return all;
    }

    /**
     * The dispatch entries this spec serializes into: one per animation model
     * (thresholds {@code 1..N}), then the loaded model (threshold {@code N+1}),
     * then the fire model (threshold {@code N+2}). Empty for a single-model
     * item. Package-private wiring for {@link PackItemDefinition}.
     */
    List<PackItemDefinition.Frame> dispatch() {
        List<PackItemDefinition.Frame> entries = new ArrayList<>();
        for (int index = 0; index < reloadReferences.size(); index++) {
            entries.add(new PackItemDefinition.Frame(index + 1, reloadReferences.get(index)));
        }
        if (loadedReference != null) {
            entries.add(new PackItemDefinition.Frame(reloadReferences.size() + 1, loadedReference));
        }
        if (fireReference != null) {
            entries.add(new PackItemDefinition.Frame(fireState(), fireReference));
        }
        return entries;
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
                && reloadReferences.equals(frames.reloadReferences);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idleReference, loadedReference, fireReference, reloadReferences);
    }

    @Override
    public String toString() {
        return (isFramed() ? "frames" : "model") + "[" + idleReference
                + (loadedReference != null ? ", loaded=" + loadedReference : "")
                + (fireReference != null ? ", fire=" + fireReference : "")
                + (reloadReferences.isEmpty() ? "" : ", animation=" + reloadReferences.size())
                + "]";
    }
}
