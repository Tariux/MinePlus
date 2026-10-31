package com.mineplus.infrastructure.virtual.rig;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.animation.VirtualBone;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Authoring shape of a bone rig: an ordered bone list where every bone names
 * its parent, carries a model-space pivot and rest rotation, and points at the
 * pack item model that renders its geometry.
 *
 * <p>Bones are stored in <b>preorder</b> (a parent always precedes its
 * children), so the renderer resolves the whole hierarchy in one forward pass
 * without recursion or lookups. Pivots are model pixels with {@code y} up —
 * the same space the {@code .bbmodel} importer bakes cubes in.</p>
 *
 * <p>A bone with no {@link Bone#geometryPath()} renders nothing; it exists only
 * to move its children (a root or an empty outliner group). A bone tagged
 * {@code hitbox} is an optional invisible ray-trace target, spawned only when
 * {@link RigSettings#hitboxBones()} is enabled.</p>
 */
public final class BoneRigDefinition {

    /** Tags the renderer understands; any other tag is informational. */
    public static final String TAG_HITBOX = "hitbox";
    public static final String TAG_MUZZLE = "muzzle";
    public static final String TAG_ATTACHMENT = "attachment";

    /**
     * One display piece of a bone. A bone normally owns one piece; a part whose
     * geometry is larger than the client's element window is emitted as several
     * pieces, each drawn by its own display. {@code offsetPx} is the piece's
     * <em>extra</em> in-window shift beyond the standard half-block anchor the
     * client's item renderer already applies (pixels, element space); the
     * renderer subtracts the rotated shift so every piece lands on the bone's
     * pivot. It is zero for a piece that needed no extra shift.
     */
    public record GeometryPiece(String path, Vector3f offsetPx) {

        public GeometryPiece {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Geometry piece path is blank");
            }
            path = trimSlashes(path);
            offsetPx = offsetPx == null ? new Vector3f() : new Vector3f(offsetPx);
        }

        public GeometryPiece(String path) {
            this(path, null);
        }
    }

    /**
     * One bone.
     *
     * @param name          stable name ({@code [a-z0-9_]} after normalization)
     * @param parentIndex   index of the parent bone, or {@code -1} for a root
     * @param pivot         pivot in model pixels ({@code y} up)
     * @param restRotation  rest rotation applied after the hierarchy transform
     * @param geometryPath  first geometry piece's path, or {@code null}
     * @param tag           optional semantic tag ({@code hitbox}, {@code muzzle}, {@code attachment})
     * @param hitboxSize    display extents for a {@code hitbox} bone, in blocks
     * @param pieces        every display piece of this bone (at most one is typical)
     */
    public record Bone(
            String name,
            int parentIndex,
            Vector3f pivot,
            Quaternionf restRotation,
            String geometryPath,
            String tag,
            Vector3f hitboxSize,
            List<GeometryPiece> pieces
    ) {

        public Bone {
            name = normalizeName(name);
            pivot = pivot == null ? new Vector3f() : new Vector3f(pivot);
            restRotation = restRotation == null ? new Quaternionf() : new Quaternionf(restRotation);
            List<GeometryPiece> normalized = new ArrayList<>();
            if (pieces != null) {
                for (GeometryPiece piece : pieces) {
                    if (piece != null) {
                        normalized.add(piece);
                    }
                }
            }
            String first = geometryPath == null || geometryPath.isBlank()
                    ? null
                    : trimSlashes(geometryPath);
            if (normalized.isEmpty() && first != null) {
                normalized.add(new GeometryPiece(first));
            }
            if (!normalized.isEmpty()) {
                first = normalized.get(0).path();
            }
            pieces = List.copyOf(normalized);
            geometryPath = first;
            tag = tag == null || tag.isBlank() ? null : tag.trim().toLowerCase(Locale.ROOT);
            hitboxSize = hitboxSize == null ? new Vector3f(1.0f, 1.0f, 1.0f) : new Vector3f(hitboxSize);
        }

        /** A bone with a single geometry piece. */
        public Bone(String name, int parentIndex, Vector3f pivot, Quaternionf restRotation,
                    String geometryPath, String tag, Vector3f hitboxSize) {
            this(name, parentIndex, pivot, restRotation, geometryPath, tag, hitboxSize, List.of());
        }

        public Bone(String name, int parentIndex, Vector3f pivot) {
            this(name, parentIndex, pivot, null, null, null, null);
        }

        public boolean isHitbox() {
            return TAG_HITBOX.equals(tag);
        }
    }

    private final String name;
    private final List<Bone> bones;
    private final int orphanBoneIndex;

    public BoneRigDefinition(String name, List<Bone> bones) {
        this(name, bones, -1);
    }

    /**
     * @param orphanBoneIndex bone that receives cubes outside any outliner
     *                        group, or {@code -1} when every cube is grouped
     */
    public BoneRigDefinition(String name, List<Bone> bones, int orphanBoneIndex) {
        this.name = name == null || name.isBlank() ? "rig" : name.trim();
        List<Bone> copy = bones == null ? List.of() : List.copyOf(bones);
        for (int i = 0; i < copy.size(); i++) {
            int parent = copy.get(i).parentIndex();
            if (parent < -1 || parent >= i) {
                throw new IllegalArgumentException("Bone '" + copy.get(i).name()
                        + "' at index " + i + " has invalid parent " + parent
                        + " (bones must be preorder)");
            }
        }
        this.bones = copy;
        this.orphanBoneIndex = orphanBoneIndex >= 0 && orphanBoneIndex < copy.size() ? orphanBoneIndex : -1;
    }

    public String name() {
        return name;
    }

    public List<Bone> bones() {
        return bones;
    }

    public int boneCount() {
        return bones.size();
    }

    public Bone bone(int index) {
        return bones.get(index);
    }

    /** Index of the bone that owns ungrouped cubes, or {@code -1}. */
    public int orphanBoneIndex() {
        return orphanBoneIndex;
    }

    /**
     * Derives a rig from an imported model: every outliner group becomes a bone
     * (inheriting its pivot and parent), bones that contain cubes reference the
     * generated per-bone geometry under {@code folder}, and ungrouped cubes get
     * one synthetic root bone so no geometry is ever dropped.
     *
     * @param name   rig/model name
     * @param model  the imported model
     * @param folder namespace-relative item-model folder for generated bone models
     */
    public static BoneRigDefinition fromModel(String name, VirtualModel model, String folder) {
        Objects.requireNonNull(model, "model");
        String base = trimSlashes(folder == null ? "entity/rig" : folder);
        List<VirtualBone> source = model.bones();
        int[] cubesPerBone = new int[source.size()];
        int orphanCubes = 0;
        for (BakedCube cube : model.cubes()) {
            int bone = cube.boneIndex();
            if (bone >= 0 && bone < source.size()) {
                cubesPerBone[bone]++;
            } else {
                orphanCubes++;
            }
        }

        List<Bone> bones = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        if (source.isEmpty()) {
            bones.add(new Bone("root", -1, new Vector3f(), new Quaternionf(),
                    uniquePath(base, "root", 0, usedPaths), null, null));
            return new BoneRigDefinition(name, bones, 0);
        }

        for (int i = 0; i < source.size(); i++) {
            VirtualBone virtualBone = source.get(i);
            String path = cubesPerBone[i] > 0
                    ? uniquePath(base, virtualBone.name(), i, usedPaths)
                    : null;
            bones.add(new Bone(virtualBone.name(), virtualBone.parentIndex(),
                    virtualBone.pivot(), null, path, null, null));
        }
        int orphanIndex = -1;
        if (orphanCubes > 0) {
            orphanIndex = bones.size();
            bones.add(new Bone("root", -1, new Vector3f(), new Quaternionf(),
                    uniquePath(base, "root", orphanIndex, usedPaths), null, null));
        }
        return new BoneRigDefinition(name, bones, orphanIndex);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    private static String uniquePath(String folder, String boneName, int index, Set<String> used) {
        String segment = sanitize(boneName);
        String candidate = folder + "/" + segment;
        if (!used.add(candidate)) {
            candidate = folder + "/" + segment + "_" + index;
            while (!used.add(candidate)) {
                candidate = candidate + "_";
            }
        }
        return candidate;
    }

    private static String sanitize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    ? c : '_');
        }
        String result = out.toString();
        return result.isEmpty() ? "bone" : result;
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "bone" : normalized;
    }

    private static String trimSlashes(String value) {
        String trimmed = value.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static final class Builder {

        private final String name;
        private final List<Bone> bones = new ArrayList<>();
        private int orphanBoneIndex = -1;

        private Builder(String name) {
            this.name = name;
        }

        public Builder bone(Bone bone) {
            bones.add(Objects.requireNonNull(bone, "bone"));
            return this;
        }

        public Builder bone(String name, int parentIndex, Vector3f pivot, String geometryPath) {
            return bone(new Bone(name, parentIndex, pivot, null, geometryPath, null, null));
        }

        /** Marks one bone as the owner of ungrouped cubes. */
        public Builder orphanBone(int index) {
            this.orphanBoneIndex = index;
            return this;
        }

        public BoneRigDefinition build() {
            return new BoneRigDefinition(name, bones, orphanBoneIndex);
        }
    }
}
