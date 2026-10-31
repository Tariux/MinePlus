package com.mineplus.infrastructure.virtual.rig;

import com.mineplus.infrastructure.virtual.BakedCube;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The immutable, per-model half of a bone rig: hierarchy, pivots, rest poses
 * and per-bone geometry references, resolved once and shared by every rendered
 * instance. Per-instance state (current local rotations, spawned display ids)
 * lives in the renderer, so a hundred instances of one model share this
 * blueprint instead of duplicating it.
 *
 * <p>Arrays are indexed by preorder bone index; {@code parent} is always
 * smaller than the child's index, so pose composition is a single forward
 * pass.</p>
 */
public final class BoneRigBlueprint {

    private final String modelKey;
    private final String namespace;
    private final BoneRigDefinition definition;
    private final String[] names;
    private final int[] parents;
    private final Vector3f[] pivots;
    private final Quaternionf[] rests;
    private final String[] geometryPaths;
    private final String[] tags;
    private final Vector3f[] hitboxSizes;
    /** Flattened display pieces: paths and authored in-window offsets, ranged per bone. */
    private final int[] pieceStart;
    private final String[] piecePaths;
    private final Vector3f[] pieceOffsets;
    private final int orphanBoneIndex;

    private BoneRigBlueprint(String modelKey, String namespace, BoneRigDefinition definition) {
        this.modelKey = modelKey;
        this.namespace = namespace == null || namespace.isBlank() ? null : namespace.trim();
        this.definition = definition;
        this.orphanBoneIndex = definition.orphanBoneIndex();
        List<BoneRigDefinition.Bone> bones = definition.bones();
        int count = bones.size();
        this.names = new String[count];
        this.parents = new int[count];
        this.pivots = new Vector3f[count];
        this.rests = new Quaternionf[count];
        this.geometryPaths = new String[count];
        this.tags = new String[count];
        this.hitboxSizes = new Vector3f[count];
        this.pieceStart = new int[count + 1];
        int totalPieces = 0;
        for (int i = 0; i < count; i++) {
            BoneRigDefinition.Bone bone = bones.get(i);
            names[i] = bone.name();
            parents[i] = bone.parentIndex();
            pivots[i] = new Vector3f(bone.pivot());
            rests[i] = new Quaternionf(bone.restRotation());
            geometryPaths[i] = bone.geometryPath();
            tags[i] = bone.tag();
            hitboxSizes[i] = new Vector3f(bone.hitboxSize());
            pieceStart[i] = totalPieces;
            totalPieces += bone.pieces().size();
        }
        pieceStart[count] = totalPieces;
        this.piecePaths = new String[totalPieces];
        this.pieceOffsets = new Vector3f[totalPieces];
        for (int i = 0; i < count; i++) {
            List<BoneRigDefinition.GeometryPiece> pieces = bones.get(i).pieces();
            for (int p = 0; p < pieces.size(); p++) {
                int slot = pieceStart[i] + p;
                piecePaths[slot] = pieces.get(p).path();
                pieceOffsets[slot] = new Vector3f(pieces.get(p).offsetPx());
            }
        }
    }

    /**
     * @param modelKey  key the renderer resolves (a multiblock level's derived key,
     *                  or a custom mob's model identity)
     * @param namespace pack namespace per-bone item models live in, or {@code null}
     */
    public static BoneRigBlueprint of(String modelKey, String namespace, BoneRigDefinition definition) {
        return new BoneRigBlueprint(modelKey, namespace, definition);
    }

    public String modelKey() {
        return modelKey;
    }

    public String namespace() {
        return namespace;
    }

    public BoneRigDefinition definition() {
        return definition;
    }

    public int boneCount() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    public int parent(int index) {
        return parents[index];
    }

    public Vector3f pivot(int index) {
        return pivots[index];
    }

    public Quaternionf rest(int index) {
        return rests[index];
    }

    public String geometryPath(int index) {
        return geometryPaths[index];
    }

    /** How many display pieces this bone renders. */
    public int pieceCount(int bone) {
        return pieceStart[bone + 1] - pieceStart[bone];
    }

    /** The pack item-model path of one display piece. */
    public String piecePath(int bone, int piece) {
        return piecePaths[pieceStart[bone] + piece];
    }

    /**
     * The extra in-window pixel shift one piece's geometry was authored with,
     * beyond the half-block anchor the client applies to every item model. The
     * renderer subtracts that rotated shift from the display position, so the
     * piece still renders around the bone's pivot.
     */
    public Vector3f pieceOffsetPx(int bone, int piece) {
        return pieceOffsets[pieceStart[bone] + piece];
    }

    /** Total display entities one instance of this rig spawns. */
    public int displaySlotCount() {
        int slots = 0;
        for (int i = 0; i < names.length; i++) {
            slots += pieceCount(i);
            if (BoneRigDefinition.TAG_HITBOX.equals(tags[i])) {
                slots++;
            }
        }
        return slots;
    }

    public String tag(int index) {
        return tags[index];
    }

    public Vector3f hitboxSize(int index) {
        return hitboxSizes[index];
    }

    public int orphanBoneIndex() {
        return orphanBoneIndex;
    }

    /** True when this bone renders geometry (or an invisible hitbox) at all. */
    public boolean hasDisplay(int index) {
        return geometryPaths[index] != null || BoneRigDefinition.TAG_HITBOX.equals(tags[index]);
    }

    /** The blueprint bone that owns a cube's geometry. */
    public int boneForCube(BakedCube cube) {
        int bone = cube == null ? -1 : cube.boneIndex();
        if (bone >= 0 && bone < names.length) {
            return bone;
        }
        return orphanBoneIndex;
    }
}
