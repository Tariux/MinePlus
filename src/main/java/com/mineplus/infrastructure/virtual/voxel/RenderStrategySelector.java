package com.mineplus.infrastructure.virtual.voxel;

import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.texel.TexelBakeResult;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Deterministic per-model rendering-strategy selection, separated from the
 * rendering implementations. The selector answers one question: should this
 * model be spawned through the existing pipeline (which internally picks
 * classic / per-face plates / texel baking exactly as before) or reconstructed
 * as a texel-aware voxel model?
 *
 * <p>Heuristics are deliberately simple and practical:
 * <ul>
 *   <li>Voxel rendering is disabled globally or per model ({@code OFF}) → the
 *       legacy pipeline keeps rendering the model unchanged.</li>
 *   <li>Animated models always keep the legacy pipeline — animations bind
 *       displays to cube bones, which a voxel reconstruction cannot represent.</li>
 *   <li>{@code ON} attempts voxelization for any non-animated model (rotated or
 *       off-lattice geometry is approximated by the baker; budgets still guard).</li>
 *   <li>{@code AUTO} only voxelizes when the reconstruction is a <i>better</i>
 *       representation, not merely a possible one: all cubes axis-aligned, all
 *       cube bounds snapped to the voxel lattice of the model's origin mode (so
 *       every occupied voxel is silhouette-exact and interior culling is exact),
 *       at least one resolvable texture PNG (otherwise voxel colors carry no
 *       texture information), and the model is not animated. Display budgets are
 *       enforced later, at bake time, by {@link VoxelSurfaceBaker}.</li>
 * </ul>
 */
public final class RenderStrategySelector {

    private RenderStrategySelector() {
    }

    /** Chosen strategy plus a human-readable rationale for diagnostics. */
    public record Selection(RenderStrategy strategy, String rationale) {
    }

    /**
     * Selects the rendering strategy for a model.
     *
     * @param model            the imported model
     * @param meta             per-model overrides ({@code voxelMode})
     * @param renderingSettings global virtual-rendering settings (per-face flag)
     * @param voxelSettings    global voxel rendering settings
     * @param texelBake        the model's texel bake result (may be {@code null});
     *                         only consulted to report the legacy sub-strategy
     * @param originMode       the model's resolved origin mode (voxel lattice)
     * @param anyTextureImage  whether any of the model's textures resolves to a
     *                         decodable PNG
     */
    public static Selection select(
            VirtualModel model,
            ModelMeta meta,
            VirtualRenderingSettings renderingSettings,
            VoxelRenderingSettings voxelSettings,
            TexelBakeResult texelBake,
            ModelMeta.OriginMode originMode,
            boolean anyTextureImage
    ) {
        ModelMeta.VoxelMode mode = voxelSettings.effectiveMode(meta);

        if (mode == ModelMeta.VoxelMode.OFF || !voxelSettings.enabled()) {
            return legacyStrategy(renderingSettings, texelBake,
                    "voxel rendering disabled ("
                            + (voxelSettings.enabled() ? "mode OFF" : "global enable off") + ")");
        }

        boolean animated = model != null && model.hasAnimations();
        if (animated) {
            if (mode == ModelMeta.VoxelMode.ON) {
                warn("Voxel rendering requested (mode ON) for animated model '"
                        + model.name()
                        + "'; keeping the legacy pipeline so cube-bone animation bindings survive.");
            }
            return legacyStrategy(renderingSettings, texelBake,
                    "model is animated (animations bind displays to cube bones)");
        }

        if (mode == ModelMeta.VoxelMode.ON) {
            return new Selection(RenderStrategy.VOXEL,
                    "forced by voxelMode ON (off-lattice geometry approximated)");
        }

        // AUTO: voxelize only when it is a strictly better representation.
        if (model == null || model.cubes().isEmpty()) {
            return legacyStrategy(renderingSettings, texelBake, "model has no geometry");
        }
        if (!allAxisAligned(model)) {
            return legacyStrategy(renderingSettings, texelBake,
                    "AUTO requires axis-aligned cubes (rotated cubes are only approximated)");
        }
        if (!gridSnapped(model, originMode)) {
            return legacyStrategy(renderingSettings, texelBake,
                    "AUTO requires cube bounds on the voxel lattice of the origin mode"
                            + " (off-lattice geometry would lose the silhouette)");
        }
        if (spansSingleBlock(model)) {
            return legacyStrategy(renderingSettings, texelBake,
                    "AUTO keeps single-block models on the cube pipeline"
                            + " (a one-block voxel reconstruction flattens per-face texture"
                            + " detail into one palette block; run merging cannot reduce"
                            + " an entity count this small)");
        }
        if (!anyTextureImage) {
            return legacyStrategy(renderingSettings, texelBake,
                    "AUTO requires at least one resolvable texture PNG"
                            + " (voxel colors come from texture sampling)");
        }
        return new Selection(RenderStrategy.VOXEL,
                "AUTO: axis-aligned, lattice-snapped, texture-backed blocky model"
                        + " (budgets enforced at bake time)");
    }

    /**
     * The strategy the existing pipeline will effectively run, for reporting:
     * TEXEL when the texel bake engaged on at least one face, FACE when
     * per-face plates are enabled, CLASSIC otherwise.
     */
    public static Selection legacyStrategy(
            VirtualRenderingSettings renderingSettings,
            TexelBakeResult texelBake,
            String voxelRationale
    ) {
        RenderStrategy legacy;
        if (texelBake != null && texelBake.enabled() && texelBake.facesBaked() > 0) {
            legacy = RenderStrategy.TEXEL;
        } else if (renderingSettings != null && renderingSettings.perFaceRendering()) {
            legacy = RenderStrategy.FACE;
        } else {
            legacy = RenderStrategy.CLASSIC;
        }
        return new Selection(legacy, voxelRationale);
    }

    /**
     * Voxel-lattice shift in model space: the min-corner offset of voxel (0,0,0).
     * GRID anchors pixel (0,0,0) at the block corner (integer lattice); CENTER
     * anchors it at the block center base, so full blocks span [-0.5..0.5] on
     * x/z and the lattice sits on half-integers there (y stays integer).
     */
    static Vector3f latticeShift(ModelMeta.OriginMode originMode) {
        return originMode == ModelMeta.OriginMode.GRID
                ? new Vector3f(0.0f, 0.0f, 0.0f)
                : new Vector3f(-0.5f, 0.0f, -0.5f);
    }

    /**
     * Whether the model's world-space AABB fits inside one block on every axis
     * (within {@code 1/256}). Single-block models keep the cube pipeline under
     * AUTO: voxelizing them trades per-face texture detail for a silhouette that
     * the cubes already represent exactly.
     */
    static boolean spansSingleBlock(VirtualModel model) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedCube cube : model.cubes()) {
            Matrix4f matrix = new Matrix4f()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = new Vector3f(
                        (corner & 1) == 0 ? 0.0f : 1.0f,
                        (corner & 2) == 0 ? 0.0f : 1.0f,
                        (corner & 4) == 0 ? 0.0f : 1.0f);
                matrix.transformPosition(p);
                if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) {
                    return false;
                }
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        float eps = 1.0f / 256.0f;
        return (maxX - minX) <= 1.0f + eps
                && (maxY - minY) <= 1.0f + eps
                && (maxZ - minZ) <= 1.0f + eps;
    }

    /**
     * Whether every cube's model-space bounds land on the voxel lattice of the
     * origin mode (integer coordinates in lattice-shifted space, within
     * {@code 1/256} block) — the precondition for a silhouette-exact voxel
     * reconstruction with exact interior culling.
     */
    static boolean gridSnapped(VirtualModel model, ModelMeta.OriginMode originMode) {
        Vector3f shift = latticeShift(originMode);
        for (BakedCube cube : model.cubes()) {
            Matrix4f matrix = new Matrix4f()
                    .translate(cube.translation())
                    .rotate(cube.leftRotation())
                    .scale(cube.scale())
                    .rotate(cube.rightRotation());
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = new Vector3f(
                        (corner & 1) == 0 ? 0.0f : 1.0f,
                        (corner & 2) == 0 ? 0.0f : 1.0f,
                        (corner & 4) == 0 ? 0.0f : 1.0f);
                matrix.transformPosition(p);
                if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) {
                    return false;
                }
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
            if (!onLattice(minX - shift.x) || !onLattice(maxX - shift.x)
                    || !onLattice(minY - shift.y) || !onLattice(maxY - shift.y)
                    || !onLattice(minZ - shift.z) || !onLattice(maxZ - shift.z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the model's geometry allows an <i>exact</i> voxel reconstruction:
     * every cube axis-aligned with lattice-snapped bounds, so each occupied
     * voxel is fully covered and interior culling cannot remove visible voxels.
     */
    static boolean exactGeometry(VirtualModel model, ModelMeta.OriginMode originMode) {
        return allAxisAligned(model) && gridSnapped(model, originMode);
    }

    private static boolean allAxisAligned(VirtualModel model) {
        for (BakedCube cube : model.cubes()) {
            if (!cube.isAxisAligned() || !isIdentity(cube.rightRotation())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentity(org.joml.Quaternionf rotation) {
        return rotation == null
                || (rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z) <= 1.0e-4f;
    }

    private static boolean onLattice(float value) {
        return Math.abs(value - Math.round(value)) <= (1.0f / 256.0f);
    }

    private static void warn(String message) {
        com.mineplus.util.DebugLogger.warning("[VoxelBaking] " + message);
    }
}
