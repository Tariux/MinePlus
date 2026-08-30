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
 *
 * <p>Faces with a baked {@link TexelSurfacePlan} (texel surface baking) emit one thin
 * plate per merged same-color rectangle instead — the face's texture reconstructed
 * pixel-by-pixel out of flat vanilla palette blocks. Texel plates share the face's
 * plane, thickness and outward offset, so they never fight each other; the in-plane UV
 * rotation is baked into the sampling, so the plate geometry itself stays axis-aligned.
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
     * Outward offset of <b>texel</b> plates from their face. Texel baking plates every
     * textured face of a model, so the plate plane and the base display's face coexist
     * at sub-millimeter depth separation everywhere — at grazing angles and moderate
     * camera distances that intermittently loses depth order and shimmers (two
     * differently-textured entities fighting in close proximity). Four times the legacy
     * separation resolves the fight while staying visually flush: 1/256 block is
     * 1/16 of a model pixel, far below any perceivable float. Sibling texel plates on
     * one face all share this single plane side-by-side, so they never fight each
     * other. <b>Keep in sync with {@code TexelSurfaceBaker}'s occlusion probe offset.</b>
     */
    public static final float TEXEL_EPS_OUT = 1.0f / 256.0f;

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
        return emitCube(cube, perFaceRendering, null);
    }

    /**
     * Emits the display list for one cube, consuming baked texel plans where present.
     *
     * <p>A face with a {@link TexelSurfacePlan} emits its merged palette plates
     * regardless of whether its resolved material matches the base display — the
     * plates <i>are</i> that face's rendering, and transparent texels show the base
     * display through. When any face has a plan, the base display itself is colored
     * with the cube's <i>dominant baked palette color</i> (largest world-area entry
     * across its plans) instead of the filename-resolved material: cutout holes then
     * reveal a matching local tone rather than the resolver's fallback (white for
     * unresolvable texture names), and untextured faces inherit it instead of plating
     * fallback material. Faces without plans keep the tiered plate logic. Emission is
     * deterministic: faces in {@link CubeFace} declaration order, a face's texel
     * plates in scan order before the next face.
     *
     * <p><b>Layered cutout cubes drop the base display.</b> A cube whose plan carries
     * genuine cutout cells (transparent glass showing the label behind) must not
     * render its full-cube base display: the base's faces sit at the cube boundary,
     * in front of every inner layer, and would z-block the layered content exactly
     * at the cutout holes — the classic "base model protruding beyond the layer
     * margins" artifact. Occluded plan cells are covered by the neighbor's own
     * geometry and cutout cells show the inner layer (or, for true silhouette
     * cutouts, the world behind), so omitting the base is visually exact. Untextured
     * faces of such a cube are plated with the dominant material so they never
     * vanish.
     *
     * @param cube             the cube to emit
     * @param perFaceRendering whether per-face plates are enabled (false = single
     *                         display; texel plates are a per-face plate tier, so they
     *                         require this too)
     * @param texelPlans       baked plans keyed by face, or {@code null} for none
     */
    public static List<EmittedDisplay> emitCube(
            BakedCube cube,
            boolean perFaceRendering,
            Map<CubeFace, TexelSurfacePlan> texelPlans
    ) {
        if (!perFaceRendering) {
            return List.of(baseDisplay(cube, primaryMaterial(cube), dominantFace(cube)));
        }

        EnumMap<CubeFace, Material> effective = effectiveMaterials(cube);
        Material base = majorityMaterial(effective);
        int dominantPalette = dominantPaletteIndex(cube, texelPlans);
        if (dominantPalette >= 0) {
            base = TexelPalette.material(dominantPalette);
        }
        boolean cutout = hasCutout(texelPlans);
        List<EmittedDisplay> output = new ArrayList<>();
        if (!cutout) {
            output.add(baseDisplay(cube, base, dominantFaceAmong(cube, effective, base)));
        }
        for (Map.Entry<CubeFace, Material> entry : effective.entrySet()) {
            CubeFace faceKey = entry.getKey();
            TexelSurfacePlan plan = texelPlans == null ? null : texelPlans.get(faceKey);
            if (plan != null) {
                output.addAll(texelDisplays(cube, faceKey, plan));
            } else if (entry.getValue() != base || cutout) {
                if (dominantPalette >= 0 && !isTextured(cube, faceKey)) {
                    if (cutout) {
                        // No base display: untextured faces plate the dominant
                        // material instead of inheriting it, so they never vanish.
                        output.addAll(plateDisplay(cube, faceKey, base));
                    }
                    continue;
                }
                output.addAll(plateDisplay(cube, faceKey, entry.getValue()));
            }
        }
        return output;
    }

    /** Whether any of the cube's baked plans carries genuine cutout cells. */
    private static boolean hasCutout(Map<CubeFace, TexelSurfacePlan> texelPlans) {
        if (texelPlans == null) {
            return false;
        }
        for (TexelSurfacePlan plan : texelPlans.values()) {
            if (plan.cutoutCells() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextured(BakedCube cube, CubeFace faceKey) {
        BakedFace face = cube.faces().get(faceKey);
        return face != null && face.textureName() != null && !face.textureName().isBlank();
    }

    /**
     * Dominant palette entry for a cube's baked plans, weighted by real world area
     * (a plan's cell area scales its contribution so a 2x2 face cannot outvote an
     * 8x8 one). {@code -1} when no plan carries a dominant entry.
     */
    private static int dominantPaletteIndex(BakedCube cube, Map<CubeFace, TexelSurfacePlan> texelPlans) {
        if (texelPlans == null || texelPlans.isEmpty()) {
            return -1;
        }
        int best = -1;
        float bestWeight = 0.0f;
        for (Map.Entry<CubeFace, TexelSurfacePlan> entry : texelPlans.entrySet()) {
            TexelSurfacePlan plan = entry.getValue();
            if (plan.dominantPaletteIndex() < 0 || plan.dominantArea() <= 0) {
                continue;
            }
            float[] pixelSize = FaceUvAnalyzer.facePixelSize(entry.getKey(), cube);
            float cellArea = (pixelSize[0] * pixelSize[1]) / (plan.gridWidth() * plan.gridHeight());
            float weight = plan.dominantArea() * cellArea;
            if (weight > bestWeight) {
                bestWeight = weight;
                best = plan.dominantPaletteIndex();
            }
        }
        return best;
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

    /**
     * Texel surface plates: one thin plate per merged same-color rectangle of a baked
     * face plan. Same subdivision math as {@link #tileDisplays} — a rectangle covering
     * grid cells {@code [x, x+w) x [y, y+h)} (row 0 = top of the UV window, plates
     * build top-down) becomes a sub-rectangle of the face with exact edge coincidence
     * on texel boundaries (small-integer divisions). All plates share one plane,
     * thickness and outward offset, so they never z-fight each other; brightness
     * follows the cube's {@code lightEmission}; block data is the palette entry's
     * cached default state (flat palette materials carry no orientation).
     */
    private static List<EmittedDisplay> texelDisplays(
            BakedCube cube, CubeFace face, TexelSurfacePlan plan) {
        int axis = face.normalAxis();
        int uAxis = face.uAxis();
        int vAxis = face.vAxis();
        boolean positive = face.positiveNormal();
        int gridWidth = plan.gridWidth();
        int gridHeight = plan.gridHeight();

        Vector3f cubeScale = new Vector3f(cube.scale());
        if (Math.abs(cubeScale.get(axis)) < 1.0e-6f) {
            cubeScale.setComponent(axis, 1.0e-6f);
        }
        float epsLocal = TEXEL_EPS_OUT / cubeScale.get(axis);
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
            plateTranslation.setComponent(axis, positive ? 1.0f + epsLocal : -epsLocal - thicknessLocal);

            Matrix4f plateMatrix = new Matrix4f(cubeMatrix)
                    .translate(plateTranslation)
                    .scale(plateScale);

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
