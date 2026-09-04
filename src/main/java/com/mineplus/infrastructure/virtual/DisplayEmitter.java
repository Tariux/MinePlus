package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.virtual.texel.TexelPalette;
import com.mineplus.infrastructure.virtual.texel.TexelSurfacePlan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * High-precision display emitter:
 * 1. Ultra-thin sub-millimeter plate skinning (1/512 block) eliminates corner gaps and cardboard protrusion.
 * 2. Flush boundary alignment eliminates overlapping penetration and edge shadow lines.
 * 3. Strict hollowness preservation: absent/untextured faces emit zero geometry.
 */
public final class DisplayEmitter {

    /** Sub-millimeter microscopic plate thickness directed inward. */
    public static final float PLATE_THICKNESS = 1.0f / 512.0f;

    /** Outward anti-z-fight offset set to zero to keep boundary surfaces strictly flush. */
    public static final float EPS_OUT = 0.0f;
    public static final float TEXEL_EPS_OUT = 0.0f;

    /** Zero seam closure prevents perpendicular meeting planes from penetrating each other and creating dark seam lines. */
    public static final float CORNER_SEAM_CLOSURE = 0.0f;

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

    public static List<EmittedDisplay> emitCube(BakedCube cube, boolean perFaceRendering) {
        return emitCube(cube, perFaceRendering, null);
    }

    public static List<EmittedDisplay> emitCube(
            BakedCube cube,
            boolean perFaceRendering,
            Map<CubeFace, TexelSurfacePlan> texelPlans
    ) {
        boolean allFacesPresent = true;
        for (CubeFace faceKey : CubeFace.values()) {
            BakedFace face = cube.faces().get(faceKey);
            if (face == null || face.textureName() == null || face.textureName().isBlank()) {
                allFacesPresent = false;
                break;
            }
        }

        boolean hasTexelPlans = texelPlans != null && !texelPlans.isEmpty();
        boolean hasCutout = hasCutout(texelPlans);
        boolean canUseBaseDisplay = !perFaceRendering && !hasTexelPlans && allFacesPresent && !hasCutout;

        List<EmittedDisplay> output = new ArrayList<>();

        if (canUseBaseDisplay) {
            return List.of(baseDisplay(cube, primaryMaterial(cube), dominantFace(cube)));
        }

        EnumMap<CubeFace, Material> effective = effectiveMaterials(cube);

        for (CubeFace faceKey : CubeFace.values()) {
            BakedFace bakedFace = cube.faces().get(faceKey);
            if (bakedFace == null) {
                // Omitted face in Blockbench: stays 100% hollow/void!
                continue;
            }

            TexelSurfacePlan plan = texelPlans == null ? null : texelPlans.get(faceKey);
            if (plan != null) {
                output.addAll(texelDisplays(cube, faceKey, plan));
                continue;
            }

            Material faceMaterial = effective.get(faceKey);
            if (faceMaterial == null) {
                continue;
            }

            output.addAll(plateDisplay(cube, faceKey, faceMaterial));
        }

        return output;
    }

    private static boolean hasCutout(Map<CubeFace, TexelSurfacePlan> texelPlans) {
        if (texelPlans == null) return false;
        for (TexelSurfacePlan plan : texelPlans.values()) {
            if (plan.cutoutCells() > 0) return true;
        }
        return false;
    }

    private static EnumMap<CubeFace, Material> effectiveMaterials(BakedCube cube) {
        EnumMap<CubeFace, Material> effective = new EnumMap<>(CubeFace.class);
        for (CubeFace faceKey : CubeFace.values()) {
            BakedFace face = cube.faces().get(faceKey);
            if (face != null && face.textureName() != null && !face.textureName().isBlank()) {
                effective.put(faceKey, TextureMaterialResolver.resolve(face.textureName()));
            }
        }
        return effective;
    }

    private static Material primaryMaterial(BakedCube cube) {
        if (cube.primaryTexture() != null && !cube.primaryTexture().isBlank()) {
            return TextureMaterialResolver.resolve(cube.primaryTexture());
        }
        return TextureMaterialResolver.fallback();
    }

    private static BakedFace dominantFace(BakedCube cube) {
        for (CubeFace faceKey : List.of(CubeFace.NORTH, CubeFace.SOUTH, CubeFace.EAST, CubeFace.WEST, CubeFace.UP, CubeFace.DOWN)) {
            BakedFace face = cube.faces().get(faceKey);
            if (face != null) return face;
        }
        return null;
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
        int uAxis = face.uAxis();
        int vAxis = face.vAxis();
        boolean positive = face.positiveNormal();
        float[] pixelSize = FaceUvAnalyzer.facePixelSize(face, cube);
        boolean squareFace = Math.abs(pixelSize[0] - pixelSize[1]) <= 1.0e-3f;

        Vector3f scale = new Vector3f(cube.scale());
        if (Math.abs(scale.get(axis)) < 1.0e-6f) scale.setComponent(axis, 1.0e-6f);
        if (Math.abs(scale.get(uAxis)) < 1.0e-6f) scale.setComponent(uAxis, 1.0e-6f);
        if (Math.abs(scale.get(vAxis)) < 1.0e-6f) scale.setComponent(vAxis, 1.0e-6f);

        float thicknessLocal = PLATE_THICKNESS / scale.get(axis);

        Vector3f plateScale = new Vector3f(1.0f, 1.0f, 1.0f);
        plateScale.setComponent(axis, thicknessLocal);

        Vector3f plateTranslation = new Vector3f();
        plateTranslation.setComponent(uAxis, 0.0f);
        plateTranslation.setComponent(vAxis, 0.0f);
        float normalStart = positive ? 1.0f - thicknessLocal : 0.0f;
        plateTranslation.setComponent(axis, normalStart);

        int inPlaneDegrees = plan.orientationDegrees();
        if (plan.half() == FaceUvAnalyzer.UvPlan.Half.LEFT || plan.half() == FaceUvAnalyzer.UvPlan.Half.RIGHT) {
            inPlaneDegrees = (inPlaneDegrees + 90) % 360;
        }
        if (!squareFace && inPlaneDegrees % 180 != 0) {
            inPlaneDegrees = 0;
        }

        if (plan.strategy() == FaceUvAnalyzer.UvPlan.Strategy.CROP_HALF
                && (plan.half() == FaceUvAnalyzer.UvPlan.Half.TOP || plan.half() == FaceUvAnalyzer.UvPlan.Half.BOTTOM)) {
            boolean topHalf = plan.half() == FaceUvAnalyzer.UvPlan.Half.TOP;
            float faceStart = positive ? 1.0f : -thicknessLocal;
            plateTranslation.setComponent(axis, positive == topHalf ? faceStart - thicknessLocal : faceStart);
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

        boolean squareTile = Math.abs(tileU - tileV) <= 1.0e-3f;
        int tileRotation = squareTile || plan.orientationDegrees() % 180 == 0 ? plan.orientationDegrees() : 0;

        Vector3f cubeScale = new Vector3f(cube.scale());
        if (Math.abs(cubeScale.get(axis)) < 1.0e-6f) cubeScale.setComponent(axis, 1.0e-6f);

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
                tileTranslation.setComponent(axis, positive ? 1.0f - thicknessLocal : 0.0f);

                Matrix4f tileMatrix = new Matrix4f(cubeMatrix).translate(tileTranslation).scale(tileScale);
                Vector3f translation = tileMatrix.getTranslation(new Vector3f());
                Vector3f worldScale = tileMatrix.getScale(new Vector3f());
                Quaternionf rotation = tileMatrix.getUnnormalizedRotation(new Quaternionf());

                if (tileRotation != 0) {
                    Quaternionf inPlane = uvRotation(-tileRotation, axis);
                    rotation = new Quaternionf(rotation).mul(inPlane);
                    Vector3f tileCenter = new Vector3f(translation).add(new Vector3f(worldScale).mul(0.5f));
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

    private static org.bukkit.block.data.BlockData blockDataFor(
            Material material, CubeFace face, FaceUvAnalyzer.UvPlan plan) {
        if (plan.strategy() == FaceUvAnalyzer.UvPlan.Strategy.CROP_HALF) {
            org.bukkit.block.data.BlockData cropped = OrientableBlockStates.halfCrop(material, plan.half());
            if (cropped != null) return cropped;
        }
        return OrientableBlockStates.oriented(material, face, plan.orientationDegrees());
    }

    private static List<EmittedDisplay> texelDisplays(
            BakedCube cube, CubeFace face, TexelSurfacePlan plan) {
        int axis = face.normalAxis();
        int uAxis = face.uAxis();
        int vAxis = face.vAxis();
        boolean positive = face.positiveNormal();
        int gridWidth = plan.gridWidth();
        int gridHeight = plan.gridHeight();

        Vector3f cubeScale = new Vector3f(cube.scale());
        if (Math.abs(cubeScale.get(axis)) < 1.0e-6f) cubeScale.setComponent(axis, 1.0e-6f);
        if (Math.abs(cubeScale.get(uAxis)) < 1.0e-6f) cubeScale.setComponent(uAxis, 1.0e-6f);
        if (Math.abs(cubeScale.get(vAxis)) < 1.0e-6f) cubeScale.setComponent(vAxis, 1.0e-6f);

        float thicknessLocal = PLATE_THICKNESS / cubeScale.get(axis);

        Matrix4f cubeMatrix = cubeMatrix(cube);
        List<EmittedDisplay> output = new ArrayList<>(plan.plateCount());

        for (TexelSurfacePlan.Rect rect : plan.plates()) {
            float uStart = rect.x() / (float) gridWidth;
            float uEnd = (rect.x() + rect.width()) / (float) gridWidth;
            float vStart = rect.y() / (float) gridHeight;
            float vEnd = (rect.y() + rect.height()) / (float) gridHeight;

            Vector3f plateScale = new Vector3f(1.0f, 1.0f, 1.0f);
            plateScale.setComponent(uAxis, uEnd - uStart);
            plateScale.setComponent(vAxis, vEnd - vStart);
            plateScale.setComponent(axis, thicknessLocal);

            Vector3f plateTranslation = new Vector3f();
            plateTranslation.setComponent(uAxis, uStart);
            plateTranslation.setComponent(vAxis, 1.0f - vEnd);
            plateTranslation.setComponent(axis, positive ? 1.0f - thicknessLocal : 0.0f);

            Matrix4f plateMatrix = new Matrix4f(cubeMatrix).translate(plateTranslation).scale(plateScale);

            output.add(new EmittedDisplay(
                    TexelPalette.material(rect.paletteIndex()),
                    plateMatrix.getTranslation(new Vector3f()),
                    plateMatrix.getUnnormalizedRotation(new Quaternionf()),
                    plateMatrix.getScale(new Vector3f()),
                    new Quaternionf(),
                    cube.lightEmission(),
                    () -> TexelPalette.blockData(rect.paletteIndex())
            ));
        }
        return output;
    }

    private static Matrix4f cubeMatrix(BakedCube cube) {
        return new Matrix4f()
                .translate(cube.translation())
                .rotate(cube.leftRotation())
                .scale(cube.scale())
                .rotate(cube.rightRotation());
    }

    private static CubeFace dominantAxisFace(BakedCube cube) {
        Vector3f s = cube.scale();
        if (s.y >= s.x && s.y >= s.z) return CubeFace.UP;
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