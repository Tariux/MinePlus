package com.mineplus.infrastructure.mob.custom.render;

import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.pack.PackApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a parsed {@link ModelData} into a registered {@link ModelBundle}: the
 * fallback chain's first two levels happen here.
 *
 * <ol>
 *   <li><b>FULL</b> — the model compiles as authored.</li>
 *   <li><b>SIMPLIFIED</b> — a model with more geometry bones than the cap is
 *       reduced deterministically: the smallest bones' cubes merge into their
 *       parents until the model fits, so fidelity drops before presentation
 *       does.</li>
 * </ol>
 *
 * <p>Anything that cannot compile at all yields a {@code BROKEN} bundle —
 * never an exception, never a rig without a report.</p>
 */
public final class ModelBundleCompiler {

    /** Ceiling of geometry bones a single rig may spawn. */
    public static final int MAX_BONES = 48;

    private ModelBundleCompiler() {
    }

    /**
     * Compiles and registers one model.
     *
     * @param namespace the mob id's namespace
     * @param modelPath the mob id's path segment
     * @param model     the parsed model (may be {@code null} — yields BROKEN)
     * @param maxBones  geometry-bone ceiling before simplification
     */
    public static ModelBundle compile(PackApi pack, String namespace, String modelPath,
                                      ModelData model, int maxBones) {
        String safePath = sanitizePath(modelPath);
        if (model == null || !model.hasGeometry()) {
            return new ModelBundle(model, namespace, safePath, ModelBundle.Fidelity.FULL,
                    PackReport.broken(model == null ? "the model did not parse"
                            : "the model has no renderable geometry"),
                    java.util.Map.of());
        }
        PackReport.Builder reportBuilder = new PackReport.Builder();
        for (String issue : model.issues()) {
            reportBuilder.repair("parse note: " + issue);
        }

        ModelData effective = model;
        ModelBundle.Fidelity fidelity = ModelBundle.Fidelity.FULL;
        int geometryBones = countGeometryBones(model);
        if (geometryBones > maxBones) {
            effective = simplify(model, maxBones);
            fidelity = ModelBundle.Fidelity.SIMPLIFIED;
            reportBuilder.repair("model has " + geometryBones + " geometry bones (cap " + maxBones
                    + "); the smallest bones merged into their parents");
        }

        PackModelEmitter.Result emitted = PackModelEmitter.emit(pack, namespace, safePath, effective);
        for (String repair : emitted.repairs()) {
            reportBuilder.repair(repair);
        }
        if (emitted.pieceCount() == 0) {
            reportBuilder.broken("no geometry could be emitted for the client's element window");
        }
        return new ModelBundle(effective, namespace, safePath, fidelity, reportBuilder.build(),
                emitted.piecesByBone());
    }

    /** Convenience overload with the default bone cap. */
    public static ModelBundle compile(PackApi pack, String namespace, String modelPath, ModelData model) {
        return compile(pack, namespace, modelPath, model, MAX_BONES);
    }

    private static int countGeometryBones(ModelData model) {
        int count = 0;
        for (ModelBone bone : model.bones()) {
            if (!bone.cubes().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Deterministic reduction: repeatedly merge the smallest-volume geometry
     * bone's cubes into its parent (re-based on the parent pivot) until the
     * bone count fits. Bone structure itself is preserved, so clip binding and
     * parent indices stay valid.
     */
    public static ModelData simplify(ModelData model, int maxBones) {
        int count = countGeometryBones(model);
        if (count <= maxBones) {
            return model;
        }
        int size = model.boneCount();
        List<List<ModelCube>> cubes = new ArrayList<>(size);
        float[] volumes = new float[size];
        for (int i = 0; i < size; i++) {
            ModelBone bone = model.bone(i);
            cubes.add(new ArrayList<>(bone.cubes()));
            float volume = 0.0f;
            for (ModelCube cube : bone.cubes()) {
                volume += cube.width() * cube.height() * cube.depth();
            }
            volumes[i] = volume;
        }

        while (count > maxBones) {
            int smallest = -1;
            float smallestVolume = Float.MAX_VALUE;
            for (int i = 1; i < size; i++) {
                if (cubes.get(i).isEmpty()) {
                    continue;
                }
                if (volumes[i] < smallestVolume) {
                    smallestVolume = volumes[i];
                    smallest = i;
                }
            }
            if (smallest < 0) {
                break;
            }
            int parent = model.bone(smallest).parentIndex();
            if (parent < 0) {
                cubes.get(smallest).clear();
            } else {
                float pivotX = model.bone(smallest).pivotX() - model.bone(parent).pivotX();
                float pivotY = model.bone(smallest).pivotY() - model.bone(parent).pivotY();
                float pivotZ = model.bone(smallest).pivotZ() - model.bone(parent).pivotZ();
                for (ModelCube cube : cubes.get(smallest)) {
                    cubes.get(parent).add(new ModelCube(
                            cube.fromX() + pivotX, cube.fromY() + pivotY, cube.fromZ() + pivotZ,
                            cube.toX() + pivotX, cube.toY() + pivotY, cube.toZ() + pivotZ,
                            cube.inflate(), cube.textureIndex(), cube.faces()));
                }
                cubes.get(smallest).clear();
            }
            count--;
        }

        List<ModelBone> bones = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ModelBone bone = model.bone(i);
            bones.add(new ModelBone(bone.name(), bone.rawName(), bone.parentIndex(),
                    bone.pivotX(), bone.pivotY(), bone.pivotZ(),
                    bone.restRotationX(), bone.restRotationY(), bone.restRotationZ(),
                    cubes.get(i), bone.attachment()));
        }
        return new ModelData(model.sourceName(), model.textureWidth(), model.textureHeight(),
                bones, model.textures(), model.animations(), model.issues());
    }

    private static String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "mob";
        }
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '/'
                    || c == '-' || c == '.' ? c : '_');
        }
        return out.toString();
    }
}
