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
 * </ul>
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
 * resting states.</p>
 */
public final class ItemFrames {

    private final String idleReference;
    private final String loadedReference;
    private final List<String> reloadReferences;

    private ItemFrames(String idleReference, String loadedReference, List<String> reloadReferences) {
        this.idleReference = requireReference(idleReference, "idle");
        this.loadedReference = loadedReference == null || loadedReference.isBlank()
                ? null
                : requireReference(loadedReference, "loaded");
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
        return new ItemFrames(idleReference, null, List.of());
    }

    /**
     * A full framed item. {@code loadedReference} is optional (null = the item
     * has no distinct loaded visual); the animation models play in order as
     * the state runs from 1 to {@code N}.
     */
    public static ItemFrames with(String idleReference, String loadedReference, List<String> reloadReferences) {
        return new ItemFrames(idleReference, loadedReference, reloadReferences);
    }

    /** The resting-state model reference; also the dispatch fallback. */
    public String idleReference() {
        return idleReference;
    }

    /** The loaded-state model reference, or {@code null} when the item has none. */
    public String loadedReference() {
        return loadedReference;
    }

    /** The animation models, in play order; empty when the item has none. */
    public List<String> reloadReferences() {
        return reloadReferences;
    }

    /** True when the item has any state beyond the resting model. */
    public boolean isFramed() {
        return loadedReference != null || !reloadReferences.isEmpty();
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
     * The animation state for a progress value in {@code [0,1]} — an even
     * walk through the animation models, ending on the last one. {@code 0} is
     * returned while the progress has not reached the first model yet; the
     * caller decides what the completed visual is (typically
     * {@link #loadedState()}).
     */
    public int reloadState(float progress) {
        int frames = reloadReferences.size();
        if (frames == 0) {
            return 0;
        }
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        return Math.min(frames, (int) Math.floor(clamped * (frames + 1)));
    }

    /** All state-relevant references (idle, loaded, animation), for diagnostics. */
    public List<String> references() {
        List<String> all = new ArrayList<>();
        all.add(idleReference);
        if (loadedReference != null) {
            all.add(loadedReference);
        }
        all.addAll(reloadReferences);
        return all;
    }

    /**
     * The dispatch entries this spec serializes into: one per animation model
     * (thresholds {@code 1..N}), then the loaded model (threshold {@code N+1}).
     * Empty for a single-model item. Package-private wiring for
     * {@link PackItemDefinition}.
     */
    List<PackItemDefinition.Frame> dispatch() {
        List<PackItemDefinition.Frame> entries = new ArrayList<>();
        for (int index = 0; index < reloadReferences.size(); index++) {
            entries.add(new PackItemDefinition.Frame(index + 1, reloadReferences.get(index)));
        }
        if (loadedReference != null) {
            entries.add(new PackItemDefinition.Frame(reloadReferences.size() + 1, loadedReference));
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
                && reloadReferences.equals(frames.reloadReferences);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idleReference, loadedReference, reloadReferences);
    }

    @Override
    public String toString() {
        return (isFramed() ? "frames" : "model") + "[" + idleReference
                + (loadedReference != null ? ", loaded=" + loadedReference : "")
                + (reloadReferences.isEmpty() ? "" : ", animation=" + reloadReferences.size())
                + "]";
    }
}
