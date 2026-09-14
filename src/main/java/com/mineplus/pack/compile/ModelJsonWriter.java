package com.mineplus.pack.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.BakedFace;
import com.mineplus.infrastructure.virtual.CubeFace;
import com.mineplus.infrastructure.virtual.ElementGeometry;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.ModelDisplay;
import com.mineplus.infrastructure.virtual.VirtualModel;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a Mineplus {@link VirtualModel} — the runtime asset graph the
 * importer already produced — into a vanilla client element-model JSON file.
 * The bbmodel stays the authoring input; this writer never re-parses it.
 *
 * <p>Space mapping: a cube's {@code translation} is its model-space offset in
 * blocks and {@code scale} its extents, relative to the anchor convention.
 * CENTER models anchor px (0,0,0) at the block center base (vanilla element
 * space shift {@code (-8, 0, -8)}), GRID models at the block corner (no
 * shift). UVs are texture pixels at the model's resolution, converted to the
 * vanilla 0..16 UV window via {@code 16 / resolution}.</p>
 *
 * <p>Honest degradation: vanilla elements are axis-aligned boxes with at most
 * one ±45° rotation, so cubes carrying a non-trivial rotation are emitted as
 * their axis-aligned bounding boxes and reported once per model — never as a
 * crash, never silently. Wrapping UV windows (span &gt; texture) are clamped
 * the same way.</p>
 */
public final class ModelJsonWriter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ModelJsonWriter() {
    }

    /**
     * @param model       the imported model
     * @param originMode  resolved anchor convention for {@code model}
     * @param outNamespace namespace the texture references point into
     */
    public static String write(VirtualModel model, ModelMeta.OriginMode originMode, String outNamespace) {
        List<String> warnings = new ArrayList<>();
        String json = write(model, originMode, outNamespace, warnings);
        for (String warning : warnings) {
            com.mineplus.util.DebugLogger.warning("[PackCompiler] Model '" + model.name() + "': " + warning);
        }
        return json;
    }

    /** Same as {@link #write(VirtualModel, ModelMeta.OriginMode, String)} with a warning sink (for tests/tooling). */
    public static String write(
            VirtualModel model,
            ModelMeta.OriginMode originMode,
            String outNamespace,
            List<String> warnings
    ) {
        return writeInternal(model, originMode, outNamespace, warnings, false);
    }

    /**
     * Serializes the model as a vanilla <em>block</em> element model — the pack
     * block axis. Identical to {@link #write} except for the coordinate
     * convention: a center-authored model is shifted by {@code +8} on x/z so
     * pixel (0,0,0) (block center) becomes the block corner the client's
     * {@code BlockDisplay} is anchored at, and no item {@code display}
     * transforms are emitted (block models carry their own vanilla defaults).
     */
    public static String writeBlock(VirtualModel model, ModelMeta.OriginMode originMode, String outNamespace) {
        List<String> warnings = new ArrayList<>();
        String json = writeBlock(model, originMode, outNamespace, warnings);
        for (String warning : warnings) {
            com.mineplus.util.DebugLogger.warning("[PackCompiler] Block model '" + model.name() + "': " + warning);
        }
        return json;
    }

    /** Same as {@link #writeBlock(VirtualModel, ModelMeta.OriginMode, String)} with a warning sink. */
    public static String writeBlock(
            VirtualModel model,
            ModelMeta.OriginMode originMode,
            String outNamespace,
            List<String> warnings
    ) {
        return writeInternal(model, originMode, outNamespace, warnings, true);
    }

    private static String writeInternal(
            VirtualModel model,
            ModelMeta.OriginMode originMode,
            String outNamespace,
            List<String> warnings,
            boolean block
    ) {
        Map<String, String> textureIndex = new LinkedHashMap<>();
        JsonArray elements = new JsonArray();
        for (BakedCube cube : model.cubes()) {
            JsonObject element = element(cube, model, originMode, textureIndex, warnings, block);
            if (element != null) {
                elements.add(element);
            }
        }

        JsonObject textures = new JsonObject();
        for (Map.Entry<String, String> entry : textureIndex.entrySet()) {
            textures.addProperty(entry.getValue(), outNamespace + ":" + entry.getKey());
        }
        if (!textureIndex.isEmpty()) {
            textures.addProperty("particle", outNamespace + ":" + textureIndex.keySet().iterator().next());
        }
        JsonObject root = new JsonObject();
        root.addProperty("credit", "Generated by Mineplus");
        root.addProperty("ambientocclusion", false);
        root.add("textures", textures);
        root.add("elements", elements);
        if (!block) {
            // Item models: optional parent for natural held/GUI transforms.
            String itemParent = model.itemParent();
            if (itemParent != null && !itemParent.isBlank()) {
                root.addProperty("parent", itemParent);
            }
            // Display: authored contexts win; if a parent is set, missing contexts
            // come from the parent. If no parent and no authored display, fall back
            // to vanilla generated defaults for compatibility.
            ModelDisplay display = model.display();
            if (display != null && !display.isEmpty()) {
                root.add("display", displayTransforms(display));
            } else if (itemParent == null) {
                root.add("display", vanillaDisplayTransforms());
            }
        }
        return GSON.toJson(root);
    }

    private static JsonObject element(
            BakedCube cube,
            VirtualModel model,
            ModelMeta.OriginMode originMode,
            Map<String, String> textureIndex,
            List<String> warnings,
            boolean block
    ) {
        ElementGeometry geom = cube.geometry();
        boolean hasNativeRotation = geom != null && geom.hasRotation() && isSingleAxisRotation(geom.rotation());

        // Warn only for non-representable rotations (multi-axis or >45°).
        if (!cube.isAxisAligned() && !hasNativeRotation) {
            warnings.add("cube '" + cube.name() + "' carries a rotation; emitted as its axis-aligned "
                    + "bounding box (vanilla elements support at most one ±45° axis rotation).");
        }

        float centerShift = originMode == ModelMeta.OriginMode.CENTER ? (block ? 8.0f : -8.0f) : 0.0f;
        float originShiftX = centerShift;
        float originShiftY = 0.0f;
        float originShiftZ = centerShift;

        float fromX, fromY, fromZ, toX, toY, toZ;
        if (geom != null) {
            // Raw authored geometry (pixels) for exact reproduction.
            fromX = geom.from().x + originShiftX;
            fromY = geom.from().y + originShiftY;
            fromZ = geom.from().z + originShiftZ;
            toX = geom.to().x + originShiftX;
            toY = geom.to().y + originShiftY;
            toZ = geom.to().z + originShiftZ;
        } else {
            // Fallback: baked box from the virtual engine.
            fromX = cube.translation().x * 16.0f + originShiftX;
            fromY = cube.translation().y * 16.0f + originShiftY;
            fromZ = cube.translation().z * 16.0f + originShiftZ;
            toX = fromX + cube.scale().x * 16.0f;
            toY = fromY + cube.scale().y * 16.0f;
            toZ = fromZ + cube.scale().z * 16.0f;
        }

        JsonObject element = new JsonObject();
        element.add("from", vec3(round(fromX), round(fromY), round(fromZ)));
        element.add("to", vec3(round(toX), round(toY), round(toZ)));
        element.addProperty("shade", false);

        // Emit native element rotation when the authored rotation is single-axis
        // and within the vanilla-supported ±45° range.
        if (hasNativeRotation) {
            char axis = dominantAxis(geom.rotation());
            float angle = switch (axis) {
                case 'x' -> geom.rotation().x;
                case 'y' -> geom.rotation().y;
                default -> geom.rotation().z;
            };
            if (Math.abs(angle) <= 45.001f) {
                JsonObject rotation = new JsonObject();
                rotation.add("origin", vec3(
                        round(geom.origin().x + originShiftX),
                        round(geom.origin().y + originShiftY),
                        round(geom.origin().z + originShiftZ)));
                rotation.addProperty("axis", axis);
                rotation.addProperty("angle", round(angle));
                rotation.addProperty("rescale", geom.rescale());
                element.add("rotation", rotation);
            } else {
                warnings.add("cube '" + cube.name() + "' rotation angle " + angle
                        + "° on " + axis + " exceeds vanilla limit (±45°); emitted as AABB.");
            }
        }

        JsonObject faces = new JsonObject();
        for (CubeFace faceKey : CubeFace.values()) {
            BakedFace face = cube.faces().get(faceKey);
            if (face == null || face.textureName() == null || face.textureName().isBlank()) {
                continue;
            }
            String texturePath = (block ? "block/" : "item/")
                    + com.mineplus.pack.asset.TextureAsset.normalizePath(face.textureName());
            if (texturePath.endsWith("/")) {
                continue;
            }
            faces.add(faceKey.name().toLowerCase(java.util.Locale.ROOT),
                    face(face, texturePath, model, textureIndex, warnings));
        }
        element.add("faces", faces);
        return element;
    }

    /** True when the rotation vector has exactly one non-zero component. */
    private static boolean isSingleAxisRotation(Vector3f rotation) {
        int nonZero = 0;
        if (Math.abs(rotation.x) > 1.0e-4f) nonZero++;
        if (Math.abs(rotation.y) > 1.0e-4f) nonZero++;
        if (Math.abs(rotation.z) > 1.0e-4f) nonZero++;
        return nonZero == 1;
    }

    /** Returns the dominant axis for a single-axis rotation ('x', 'y', or 'z'). */
    private static char dominantAxis(Vector3f rotation) {
        if (Math.abs(rotation.x) > 1.0e-4f) return 'x';
        if (Math.abs(rotation.y) > 1.0e-4f) return 'y';
        return 'z';
    }

    private static JsonObject face(
            BakedFace face,
            String texturePath,
            VirtualModel model,
            Map<String, String> textureIndex,
            List<String> warnings
    ) {
        // Guard against broken imports: a zero resolution would divide by
        // zero and write NaN/Infinity — invalid JSON the client rejects.
        int resolutionWidth = Math.max(1, model.resolution().width());
        int resolutionHeight = Math.max(1, model.resolution().height());
        float uScale = 16.0f / resolutionWidth;
        float vScale = 16.0f / resolutionHeight;

        // Preserve authored UV orientation: vanilla mirrors when u2<u1 or v2<v1.
        // Do NOT swap; the original ordering encodes intentional mirrors.
        float u1 = clampUv(face.u1() * uScale, warnings, face);
        float v1 = clampUv(face.v1() * vScale, warnings, face);
        float u2 = clampUv(face.u2() * uScale, warnings, face);
        float v2 = clampUv(face.v2() * vScale, warnings, face);

        // Standard vanilla texture-variable naming: the textures map declares
        // "0", faces reference "#0". (Using the reference itself as the map key
        // leaves the client unable to resolve the variable.)
        String index = textureIndex.computeIfAbsent(texturePath,
                name -> String.valueOf(textureIndex.size()));

        JsonObject json = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(round(u1));
        uv.add(round(v1));
        uv.add(round(u2));
        uv.add(round(v2));
        json.add("uv", uv);
        json.addProperty("texture", "#" + index);
        int rotation = ((face.rotation() % 360) + 360) % 360;
        if (rotation != 0) {
            json.addProperty("rotation", rotation);
        }
        return json;
    }

    private static float clampUv(float value, List<String> warnings, BakedFace face) {
        if (value < -1.0e-3f || value > 16.0f + 1.0e-3f) {
            warnings.add("face texture '" + face.textureName() + "' has a wrapping UV window; clamped to 0..16.");
        }
        return Math.max(0.0f, Math.min(16.0f, value));
    }

    /** Emits the authored display contexts, ordered deterministically. */
    private static JsonObject displayTransforms(ModelDisplay display) {
        JsonObject json = new JsonObject();
        List<String> contexts = new ArrayList<>(display.contexts().keySet());
        contexts.sort(java.util.Comparator.naturalOrder());
        for (String context : contexts) {
            ModelDisplay.Transform transform = display.contexts().get(context);
            json.add(context, displayEntry(
                    new float[]{transform.rotation().x, transform.rotation().y, transform.rotation().z},
                    new float[]{transform.translation().x, transform.translation().y, transform.translation().z},
                    new float[]{transform.scale().x, transform.scale().y, transform.scale().z}));
        }
        return json;
    }

    private static JsonObject vanillaDisplayTransforms() {
        JsonObject display = new JsonObject();
        display.add("gui", displayEntry(new float[]{30, 225, 0}, new float[]{0, 0, 0}, new float[]{0.625f, 0.625f, 0.625f}));
        display.add("ground", displayEntry(new float[]{0, 0, 0}, new float[]{0, 3, 0}, new float[]{0.25f, 0.25f, 0.25f}));
        display.add("head", displayEntry(new float[]{0, 180, 0}, new float[3], new float[]{1, 1, 1}));
        display.add("fixed", displayEntry(new float[3], new float[3], new float[]{1, 1, 1}));
        display.add("thirdperson_righthand", displayEntry(new float[]{75, 45, 0}, new float[]{0, 2.5f, 0}, new float[]{0.375f, 0.375f, 0.375f}));
        display.add("thirdperson_lefthand", displayEntry(new float[]{75, 225, 0}, new float[]{0, 2.5f, 0}, new float[]{0.375f, 0.375f, 0.375f}));
        display.add("firstperson_righthand", displayEntry(new float[]{0, 45, 0}, new float[3], new float[]{0.40f, 0.40f, 0.40f}));
        display.add("firstperson_lefthand", displayEntry(new float[]{0, 225, 0}, new float[3], new float[]{0.40f, 0.40f, 0.40f}));
        return display;
    }

    private static JsonObject displayEntry(float[] rotation, float[] translation, float[] scale) {
        JsonObject entry = new JsonObject();
        entry.add("rotation", vec3(rotation));
        entry.add("translation", vec3(translation));
        entry.add("scale", vec3(scale));
        return entry;
    }

    private static JsonArray vec3(float... values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(round(value));
        }
        return array;
    }

    private static float round(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }
}
