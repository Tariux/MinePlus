package com.mineplus.infrastructure.mob.custom.render;

import com.mineplus.infrastructure.mob.custom.model.ModelData;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;

/**
 * The compiled, pack-presentable form of one custom mob's model: the parsed
 * {@link ModelData} (bones, pivots, clips) plus where each bone's geometry
 * lives in the pack and whether the presentation passed validation.
 *
 * <p>A bundle exists even when its model is broken — its report then says
 * {@code BROKEN} and the engine knows to keep the fallback visible instead of
 * attaching a rig. A model never reaches a display without
 * {@link PackReport#usable()}.</p>
 */
public final class ModelBundle {

    /** How much of the authored model the bundle carries. */
    public enum Fidelity {
        /** Every bone as authored. */
        FULL,
        /** Geometry reduced (merged into parents) to respect the bone cap. */
        SIMPLIFIED
    }

    private final ModelData model;
    private final String namespace;
    private final String modelPath;
    private final Fidelity fidelity;
    private final PackReport report;
    private final Map<Integer, List<PackModelEmitter.Piece>> piecesByBone;

    public ModelBundle(ModelData model, String namespace, String modelPath, Fidelity fidelity,
                       PackReport report, Map<Integer, List<PackModelEmitter.Piece>> piecesByBone) {
        this.model = model;
        this.namespace = namespace;
        this.modelPath = modelPath;
        this.fidelity = fidelity == null ? Fidelity.FULL : fidelity;
        this.report = report == null ? PackReport.ok() : report;
        this.piecesByBone = Map.copyOf(piecesByBone);
    }

    public ModelData model() {
        return model;
    }

    public String namespace() {
        return namespace;
    }

    public String modelPath() {
        return modelPath;
    }

    public Fidelity fidelity() {
        return fidelity;
    }

    public PackReport report() {
        return report;
    }

    /** The display pieces of one bone (may be empty). */
    public List<PackModelEmitter.Piece> pieces(int boneIndex) {
        return piecesByBone.getOrDefault(boneIndex, List.of());
    }

    public int pieceCount() {
        int count = 0;
        for (List<PackModelEmitter.Piece> pieces : piecesByBone.values()) {
            count += pieces.size();
        }
        return count;
    }

    /** Total displays a rig of this bundle spawns (pieces + hand items). */
    public int displayEstimate(int handItemCount) {
        return pieceCount() + Math.max(0, handItemCount);
    }

    /** True when the bundle may be attached as a rig. */
    public boolean presentable() {
        return report.usable() && pieceCount() > 0;
    }

    /** A one-line status for commands and probes. */
    public String statusLine() {
        return fidelity.name().toLowerCase(java.util.Locale.ROOT) + ", " + pieceCount() + " piece(s): "
                + report.summary();
    }

    /** Vector copy helper for piece offsets. */
    public static Vector3f copy(Vector3f value) {
        return value == null ? new Vector3f() : new Vector3f(value);
    }
}
