package com.mineplus.infrastructure.virtual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Material;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-cube display emission (box/UV mapping parity).
 *
 * <p>Fast path (uniform material across all faces, or UV texture mode): a single
 * {@code BlockDisplay} per cube — identical entity budget to the legacy renderer.
 *
 * <p>Split path (box mapping with differing face materials): one base display in the
 * primary material plus a thin overlay plate per differing face. A plate is a display of
 * the face's material scaled to the face rectangle with one-pixel thickness, hugging the
 * face with a 1/1024 anti-z-fight outward offset, rotated in-plane by the face's UV
 * rotation (90-degree multiples; exact for square faces, closest-approximation otherwise
 * since display entities cannot rotate a texture independently of geometry).
 */
public final class DisplayEmitter {

    /** Plate thickness in blocks (one pixel). */
    public static final float PLATE_THICKNESS = 1.0f / 16.0f;

    /** Outward offset of plates from their face to avoid z-fighting. */
    public static final float EPS_OUT = 1.0f / 1024.0f;

    /** One emitted display: material + TRS + brightness, entity-agnostic for testability. */
    public record EmittedDisplay(
            Material material,
            Vector3f translation,
            Quaternionf leftRotation,
            Vector3f scale,
            Quaternionf rightRotation,
            int lightEmission,
            String source
    ) {
    }

    /** Per-material BlockData customization for blocks whose default state is degenerate. */
    private static final Map<Material, Function<Material, org.bukkit.block.data.BlockData>> BLOCK_DATA_OVERRIDES =
            Map.of();

    private DisplayEmitter() {
    }

    public static org.bukkit.block.data.BlockData blockDataFor(Material material) {
        Function<Material, org.bukkit.block.data.BlockData> override = BLOCK_DATA_OVERRIDES.get(material);
        return override != null ? override.apply(material) : material.createBlockData();
    }

    /**
     * Emits the display list for one cube.
     *
     * @param model        owning model (UV texture mode consults its default texture)
     * @param cube         the cube to emit
     * @param textureMode  effective texture mode (BOX or UV)
     * @param perFaceRendering whether split-path plates are enabled at all
     */
    public static List<EmittedDisplay> emitCube(VirtualModel model, BakedCube cube,
                                                VirtualModel.TextureMode textureMode,
                                                boolean perFaceRendering) {
        List<EmittedDisplay> output = new ArrayList<>();
        if (textureMode == VirtualModel.TextureMode.UV) {
            String uvTexture = model.uvTextureName();
            Material material = TextureMaterialResolver.resolve(uvTexture);
            output.add(baseDisplay(cube, material, cube.name()));
            return output;
        }

        EnumMap<CubeFace, Material> faceMaterials = new EnumMap<>(CubeFace.class);
        Material primary = null;
        if (cube.primaryTexture() != null && !cube.primaryTexture().isBlank()) {
            primary = TextureMaterialResolver.resolve(cube.primaryTexture());
        }
        for (Map.Entry<CubeFace, BakedFace> entry : cube.faces().entrySet()) {
            String textureName = entry.getValue().textureName();
            if (textureName == null || textureName.isBlank()) {
                continue;
            }
            faceMaterials.put(entry.getKey(), TextureMaterialResolver.resolve(textureName));
        }

        if (!perFaceRendering || distinctMaterials(faceMaterials, primary) <= 1) {
            output.add(baseDisplay(cube, primary != null ? primary : TextureMaterialResolver.fallback(), cube.name()));
            return output;
        }

        output.add(baseDisplay(cube, primary, cube.name() + ":base"));
        for (Map.Entry<CubeFace, Material> entry : faceMaterials.entrySet()) {
            if (entry.getValue() == primary) {
                continue;
            }
            BakedFace face = cube.faces().get(entry.getKey());
            int uvRotation = face != null ? face.rotation() : 0;
            output.add(plateDisplay(cube, entry.getKey(), entry.getValue(), uvRotation));
        }
        return output;
    }

    private static int distinctMaterials(Map<CubeFace, Material> faceMaterials, Material primary) {
        java.util.Set<Material> distinct = new java.util.HashSet<>(faceMaterials.values());
        if (primary != null) {
            distinct.add(primary);
        }
        return distinct.size();
    }

    private static EmittedDisplay baseDisplay(BakedCube cube, Material material, String source) {
        return new EmittedDisplay(
                material,
                new Vector3f(cube.translation()),
                new Quaternionf(cube.leftRotation()),
                new Vector3f(cube.scale()),
                new Quaternionf(cube.rightRotation()),
                cube.lightEmission(),
                source
        );
    }

    private static EmittedDisplay plateDisplay(BakedCube cube, CubeFace face, Material material, int uvRotationDegrees) {
        int axis = face.normalAxis();
        boolean positive = face.positiveNormal();

        Vector3f scale = new Vector3f(cube.scale());
        if (Math.abs(scale.get(axis)) < 1.0e-6f) {
            scale.setComponent(axis, 1.0e-6f);
        }
        float epsLocal = EPS_OUT / scale.get(axis);
        float thicknessLocal = PLATE_THICKNESS / scale.get(axis);

        // Plate box in cube-local unit space: full [0,1] footprint on tangent axes.
        Vector3f plateScale = new Vector3f(1.0f, 1.0f, 1.0f);
        plateScale.setComponent(axis, thicknessLocal);
        Vector3f plateTranslation = new Vector3f();
        float normalStart = positive ? 1.0f + epsLocal : -epsLocal - thicknessLocal;
        plateTranslation.setComponent(axis, normalStart);

        // Plate center (pivot for in-plane UV rotation).
        Vector3f center = new Vector3f(plateTranslation).add(new Vector3f(plateScale).mul(0.5f));

        // M_plate_local = T(center) * R_axis(-uv) * S(plateScale) * T(-1/2)
        // (the trailing T recenters the unit cube so the rotation pivots on the plate center).
        Matrix4f local = new Matrix4f()
                .translate(center)
                .rotate(uvRotation(-uvRotationDegrees, axis))
                .scale(plateScale)
                .translate(-0.5f, -0.5f, -0.5f);

        // M_cube * M_plate_local, decomposed to TRS.
        Matrix4f cubeMatrix = new Matrix4f()
                .translate(cube.translation())
                .rotate(cube.leftRotation())
                .scale(cube.scale())
                .rotate(cube.rightRotation());
        Matrix4f plateMatrix = new Matrix4f(cubeMatrix).mul(local);

        Vector3f translation = plateMatrix.getTranslation(new Vector3f());
        Vector3f plateWorldScale = plateMatrix.getScale(new Vector3f());
        Quaternionf rotation = plateMatrix.getUnnormalizedRotation(new Quaternionf());

        return new EmittedDisplay(
                material,
                translation,
                rotation,
                plateWorldScale,
                new Quaternionf(),
                cube.lightEmission(),
                cube.name() + ":" + face.name().toLowerCase(Locale.ROOT)
        );
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
