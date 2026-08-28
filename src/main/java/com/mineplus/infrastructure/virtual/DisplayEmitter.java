package com.mineplus.infrastructure.virtual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-cube display emission (box mapping parity).
 *
 * <p>Every face carries an <i>effective material</i>: explicitly textured faces resolve
 * their own texture; untextured or undefined faces stay a smooth neutral surface
 * ({@code WHITE_CONCRETE}) — a face without a texture reference never inherits another
 * face's texture. The base display renders the majority material; only minority faces
 * receive thin overlay plates, minimizing both entity count and added surface geometry.
 *
 * <p>A plate hugs its face rectangle exactly (edges coincide with the geometry, no
 * seams), with a thin skin thickness and a 1/1024 anti-z-fight outward offset. UV
 * windows render at the highest exactly-implementable tier
 * (see {@link FaceUvAnalyzer}): wrapping windows as native-density tile grids,
 * half windows as slab-type crops with geometry compensation, other windows as the
 * full texture. In-plane UV rotation is applied only when the plate footprint is
 * invariant under it (square faces, or pure 180°).
 */
public final class DisplayEmitter {

    /**
     * Plate skin thickness in blocks. Deliberately thin (1/64 block): the plate's edges
     * are visible as a band on the cube surface, so thicker plates read as added
     * geometry on smooth surfaces.
     */
    public static final float PLATE_THICKNESS = 1.0f / 64.0f;

    /** Outward offset of plates from their face to avoid z-fighting. */
    public static final float EPS_OUT = 1.0f / 1024.0f;

    /**
     * One emitted display: material + TRS + brightness, entity-agnostic. The block data
     * is created lazily so geometry-only consumers never need a Bukkit server.
     */
    public record EmittedDisplay(
            Material material,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation,
            int lightEmission,
            java.util.function.Supplier<org.bukkit.block.data.BlockData> blockDataSupplier
    ) {

        public org.bukkit.block.data.BlockData blockData() {
            return blockDataSupplier.get();
        }
    }

    private DisplayEmitter() {
    }

    /**
     * Emits the display list for one cube.
     *
     * @param cube             the cube to emit
     * @param perFaceRendering whether per-face plates are enabled (false = single
     *                         display in the primary texture's material)
     */
    public static List<EmittedDisplay> emitCube(BakedCube cube, boolean perFaceRendering) {
        if (!perFaceRendering) {
            return List.of(baseDisplay(cube, primaryMaterial(cube), dominantFace(cube)));
        }

        EnumMap<CubeFace, Material> effective = effectiveMaterials(cube);
        Material base = majorityMaterial(effective);
        List<EmittedDisplay> output = new ArrayList<>();
        output.add(baseDisplay(cube, base, dominantFaceAmong(cube, effective, base)));
        for (Map.Entry<CubeFace, Material> entry : effective.entrySet()) {
            if (entry.getValue() != base) {
                output.addAll(plateDisplay(cube, entry.getKey(), entry.getValue()));
            }
        }
        return output;
    }

    /** Effective material per face: own texture when defined, else the neutral surface. */
    private static EnumMap<CubeFace, Material> effectiveMaterials(BakedCube cube) {
        EnumMap<CubeFace, Material> effective = new EnumMap<>(CubeFace.class);
        for (CubeFace faceKey : CubeFace.values()) {
            BakedFace face = cube.faces().get(faceKey);
            boolean textured = face != null && face.textureName() != null && !face.textureName().isBlank();
            effective.put(faceKey, textured
                    ? TextureMaterialResolver.resolve(face.textureName())
                    : TextureMaterialResolver.fallback());
        }
        return effective;
    }

    private static Material primaryMaterial(BakedCube cube) {
        if (cube.primaryTexture() != null && !cube.primaryTexture().isBlank()) {
            return TextureMaterialResolver.resolve(cube.primaryTexture());
        }
        return TextureMaterialResolver.fallback();
    }

    /** Majority effective face material; ties prefer a textured material over neutral. */
    private static Material majorityMaterial(EnumMap<CubeFace, Material> effective) {
        Material fallback = TextureMaterialResolver.fallback();
        EnumMap<Material, Integer> counts = new EnumMap<>(Material.class);
        for (Material material : effective.values()) {
            counts.merge(material, 1, Integer::sum);
        }
        Material best = fallback;
        int bestCount = -1;
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            Material material = entry.getKey();
            int count = entry.getValue();
            boolean better = count > bestCount
                    || (count == bestCount && best == fallback && material != fallback);
            if (better) {
                best = material;
                bestCount = count;
            }
        }
        return best;
    }

    /** First defined face in the primary-texture selection order. */
    private static BakedFace dominantFace(BakedCube cube) {
        for (CubeFace faceKey : List.of(CubeFace.NORTH, CubeFace.SOUTH, CubeFace.EAST,
                CubeFace.WEST, CubeFace.UP, CubeFace.DOWN)) {
            BakedFace face = cube.faces().get(faceKey);
            if (face != null) {
                return face;
            }
        }
        return null;
    }

    /** First defined face (in primary order) whose effective material equals the given one. */
    private static BakedFace dominantFaceAmong(BakedCube cube, EnumMap<CubeFace, Material> effective,
                                               Material material) {
        for (CubeFace faceKey : List.of(CubeFace.NORTH, CubeFace.SOUTH, CubeFace.EAST,
                CubeFace.WEST, CubeFace.UP, CubeFace.DOWN)) {
            if (effective.get(faceKey) == material) {
                BakedFace face = cube.faces().get(faceKey);
                if (face != null) {
                    return face;
                }
            }
        }
        return dominantFace(cube);
    }

    private static EmittedDisplay baseDisplay(BakedCube cube, Material material, BakedFace face) {
        int rotation = face != null ? face.rotation() : 0;
        CubeFace axisFace = dominantAxisFace(cube);
        return new EmittedDisplay(
                material,
                new Vector3f(cube.translation()),
                new Quaternionf(cube.leftRotation()),
                new Vector3f(cube.scale()),
                new Quaternionf(cube.rightRotation()),
                cube.lightEmission(),
                () -> OrientableBlockStates.oriented(material, axisFace, rotation)
        );
    }

    private static List<EmittedDisplay> plateDisplay(BakedCube cube, CubeFace face, Material material) {
        BakedFace bakedFace = cube.faces().get(face);
        FaceUvAnalyzer.UvPlan plan = FaceUvAnalyzer.analyze(bakedFace);

        if (plan.strategy() == FaceUvAnalyzer.UvPlan.Strategy.TILE) {
            return tileDisplays(cube, face, material, plan);
        }

        int axis = face.normalAxis();
        boolean positive = face.positiveNormal();
        float[] pixelSize = FaceUvAnalyzer.facePixelSize(face, cube);
        boolean squareFace = Math.abs(pixelSize[0] - pixelSize[1]) <= 1.0e-3f;

        Vector3f scale = new Vector3f(cube.scale());
        if (Math.abs(scale.get(axis)) < 1.0e-6f) {
            scale.setComponent(axis, 1.0e-6f);
        }
        float epsLocal = EPS_OUT / scale.get(axis);
        float thicknessLocal = PLATE_THICKNESS / scale.get(axis);

        // Plate box in cube-local unit space: full-face footprint on tangent axes,
        // thin skin on the normal axis — edges coincide exactly with the face.
        Vector3f plateScale = new Vector3f(1.0f, 1.0f, 1.0f);
        plateScale.setComponent(axis, thicknessLocal);
        Vector3f plateTranslation = new Vector3f();
        float normalStart = positive ? 1.0f + epsLocal : -epsLocal - thicknessLocal;
        plateTranslation.setComponent(axis, normalStart);

        // In-plane UV rotation, guarded: only applied when the footprint is invariant
        // under it. Horizontal half-crops map onto vertical slab halves (+90°) first.
        int inPlaneDegrees = plan.orientationDegrees();
        if (plan.half() == FaceUvAnalyzer.UvPlan.Half.LEFT
                || plan.half() == FaceUvAnalyzer.UvPlan.Half.RIGHT) {
            inPlaneDegrees = (inPlaneDegrees + 90) % 360;
        }
        if (!squareFace && inPlaneDegrees % 180 != 0) {
            inPlaneDegrees = 0;
        }

        // Half-crop geometry compensation: a TOP/BOTTOM slab renders its texture on
        // only the top/bottom half of the unit block, so the plate doubles its
        // normal-axis thickness and shifts back, making the visible textured half
        // coincide with the face rectangle.
        if (plan.strategy() == FaceUvAnalyzer.UvPlan.Strategy.CROP_HALF
                && (plan.half() == FaceUvAnalyzer.UvPlan.Half.TOP
                || plan.half() == FaceUvAnalyzer.UvPlan.Half.BOTTOM)) {
            boolean topHalf = plan.half() == FaceUvAnalyzer.UvPlan.Half.TOP;
            plateTranslation.setComponent(axis,
                    positive == topHalf ? normalStart - thicknessLocal : normalStart);
            plateScale.setComponent(axis, thicknessLocal * 2.0f);
        }

        Vector3f center = new Vector3f(plateTranslation).add(new Vector3f(plateScale).mul(0.5f));
        Matrix4f local = new Matrix4f()
                .translate(center)
                .rotate(uvRotation(-inPlaneDegrees, axis))
                .scale(plateScale)
                .translate(-0.5f, -0.5f, -0.5f);

        Matrix4f plateMatrix = new Matrix4f(cubeMatrix(cube)).mul(local);
        return List.of(new EmittedDisplay(
                material,
                plateMatrix.getTranslation(new Vector3f()),
                plateMatrix.getUnnormalizedRotation(new Quaternionf()),
                plateMatrix.getScale(new Vector3f()),
                new Quaternionf(),
                cube.lightEmission(),
                () -> blockDataFor(material, face, plan)
        ));
    }

    /**
     * Native-density tiling for wrapping UV windows: the face is covered by a
     * {@code uTiles x vTiles} grid of plate displays, each exactly one texture tile,
     * so pixel density matches Blockbench's wrapped rendering with zero stretching.
     */
    private static List<EmittedDisplay> tileDisplays(
            BakedCube cube, CubeFace face, Material material, FaceUvAnalyzer.UvPlan plan) {
        int axis = face.normalAxis();
        int uAxis = face.uAxis();
        int vAxis = face.vAxis();
        boolean positive = face.positiveNormal();

        float[] pixelSize = FaceUvAnalyzer.facePixelSize(face, cube);
        int uTiles = Math.max(1, plan.uTiles());
        int vTiles = Math.max(1, plan.vTiles());
        float tileU = pixelSize[0] / uTiles;
        float tileV = pixelSize[1] / vTiles;

        // A rectangular tile cannot rotate 90° without escaping its grid cell.
        boolean squareTile = Math.abs(tileU - tileV) <= 1.0e-3f;
        int tileRotation = squareTile || plan.orientationDegrees() % 180 == 0
                ? plan.orientationDegrees()
                : 0;

        Vector3f cubeScale = new Vector3f(cube.scale());
        if (Math.abs(cubeScale.get(axis)) < 1.0e-6f) {
            cubeScale.setComponent(axis, 1.0e-6f);
        }
        float epsLocal = EPS_OUT / cubeScale.get(axis);
        float thicknessLocal = PLATE_THICKNESS / cubeScale.get(axis);

        Matrix4f cubeMatrix = cubeMatrix(cube);
        List<EmittedDisplay> tiles = new ArrayList<>(uTiles * vTiles);
        for (int tv = 0; tv < vTiles; tv++) {
            for (int tu = 0; tu < uTiles; tu++) {
                float uStart = tu / (float) uTiles;
                float uEnd = (tu + 1) / (float) uTiles;
                float vStart = tv / (float) vTiles;
                float vEnd = (tv + 1) / (float) vTiles;

                Vector3f tileScale = new Vector3f(1.0f, 1.0f, 1.0f);
                tileScale.setComponent(uAxis, uEnd - uStart);
                tileScale.setComponent(vAxis, vEnd - vStart);
                tileScale.setComponent(axis, thicknessLocal);

                Vector3f tileTranslation = new Vector3f();
                tileTranslation.setComponent(uAxis, uStart);
                tileTranslation.setComponent(vAxis, 1.0f - vEnd);
                tileTranslation.setComponent(axis, positive ? 1.0f + epsLocal : -epsLocal - thicknessLocal);

                Matrix4f tileMatrix = new Matrix4f(cubeMatrix)
                        .translate(tileTranslation)
                        .scale(tileScale);

                Vector3f translation = tileMatrix.getTranslation(new Vector3f());
                Vector3f worldScale = tileMatrix.getScale(new Vector3f());
                Quaternionf rotation = tileMatrix.getUnnormalizedRotation(new Quaternionf());

                if (tileRotation != 0) {
                    Quaternionf inPlane = uvRotation(-tileRotation, axis);
                    rotation = new Quaternionf(rotation).mul(inPlane);
                    Vector3f tileCenter = new Vector3f(translation)
                            .add(new Vector3f(worldScale).mul(0.5f));
                    Vector3f offset = new Vector3f(translation).sub(tileCenter);
                    inPlane.transform(offset);
                    translation = new Vector3f(tileCenter).add(offset);
                }

                tiles.add(new EmittedDisplay(
                        material,
                        translation,
                        rotation,
                        worldScale,
                        new Quaternionf(),
                        cube.lightEmission(),
                        () -> OrientableBlockStates.oriented(material, face, plan.orientationDegrees())
                ));
            }
        }
        return tiles;
    }

    /** Half-crop slab when available, else the oriented block state. */
    private static org.bukkit.block.data.BlockData blockDataFor(
            Material material, CubeFace face, FaceUvAnalyzer.UvPlan plan) {
        if (plan.strategy() == FaceUvAnalyzer.UvPlan.Strategy.CROP_HALF) {
            org.bukkit.block.data.BlockData cropped = OrientableBlockStates.halfCrop(material, plan.half());
            if (cropped != null) {
                return cropped;
            }
        }
        return OrientableBlockStates.oriented(material, face, plan.orientationDegrees());
    }

    private static Matrix4f cubeMatrix(BakedCube cube) {
        return new Matrix4f()
                .translate(cube.translation())
                .rotate(cube.leftRotation())
                .scale(cube.scale())
                .rotate(cube.rightRotation());
    }

    /** Face whose normal matches the cube's longest axis: log ends / furnace fronts. */
    private static CubeFace dominantAxisFace(BakedCube cube) {
        Vector3f s = cube.scale();
        if (s.y >= s.x && s.y >= s.z) {
            return CubeFace.UP;
        }
        return s.x >= s.z ? CubeFace.EAST : CubeFace.SOUTH;
    }

    private static Quaternionf uvRotation(int degrees, int axis) {
        Quaternionf q = new Quaternionf();
        float radians = (float) Math.toRadians(degrees);
        switch (axis) {
            case 0 -> q.rotationX(radians);
            case 1 -> q.rotationY(radians);
            default -> q.rotationZ(radians);
        }
        return q;
    }
}
