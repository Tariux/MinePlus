package com.mineplus.infrastructure.mob.custom.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelTexture;
import com.mineplus.infrastructure.virtual.rig.BoneRigAssetWriter;
import com.mineplus.pack.PackApi;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;

/**
 * Compiles a parsed {@link ModelData} into pack assets — one item model per
 * bone (split into in-window pieces where a bone spans the client's element
 * window), the texture PNGs, and the item definitions an {@code ItemDisplay}
 * needs — through the same {@link BoneRigAssetWriter#registerGenerated}
 * convention the pack rig pipeline uses, so both representations can never
 * drift.
 *
 * <p><b>Anchor contract.</b> The client's item renderer draws every element
 * translated by half a block, so a bone's geometry is authored as
 * {@code (q - pivot) + 8px}: the display sits at the bone's world pivot and
 * its matrix rotates the geometry about that pivot. Unlike a naive emitter,
 * nothing is re-centred — a part displaced from its pivot animates around the
 * wrong point.</p>
 *
 * <p><b>Element window.</b> The client accepts element coordinates in
 * {@code -16..32} px per axis. A bone whose anchored geometry overflows is
 * split along the overflowing axis into contiguous groups; each group carries
 * the smallest extra in-window shift, and the shift is handed back to the
 * renderer (rotated with the bone) so the split stays invisible.</p>
 *
 * <p><b>Rotations.</b> Elements are axis-aligned by construction: the parsers
 * bake element rotations into synthetic sub-bones, because the client's
 * element rotation is limited to one axis at ±45° while a bone rotation is
 * not limited.</p>
 *
 * <p>Output is deterministic for identical input — same bytes, so the pack
 * hash only changes when a model really changes.</p>
 */
public final class PackModelEmitter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Namespace-relative folder every generated mob rig asset lives under. */
    public static final String ROOT = "mob/";

    /** The client's element window: valid coordinates are {@code -16 .. 32} px per axis. */
    public static final float WINDOW_MIN = -16.0f;
    public static final float WINDOW_MAX = 32.0f;
    private static final float WINDOW_SPAN = WINDOW_MAX - WINDOW_MIN;

    /**
     * Half a block in pixels: the client translates item element space by
     * {@code (-0.5, -0.5, -0.5)} block, so pivot-relative geometry is authored
     * half a block off to land on the display position.
     */
    public static final float ANCHOR_PX = 8.0f;

    private static final int MAX_SPLIT_DEPTH = 6;
    private static final String[] FACE_DIRECTIONS = {"north", "south", "east", "west", "up", "down"};

    private PackModelEmitter() {
    }

    /** One display piece of one bone: its component path and extra in-window shift. */
    public record Piece(String componentPath, Vector3f offsetPx) {
    }

    /** The emitted assets: display pieces by bone index (emission order), plus repairs. */
    public record Result(Map<Integer, List<Piece>> piecesByBone, List<String> repairs) {

        public int pieceCount() {
            int count = 0;
            for (List<Piece> pieces : piecesByBone.values()) {
                count += pieces.size();
            }
            return count;
        }
    }

    /**
     * Emits every asset of one model and registers it with the pack.
     *
     * @param pack      the pack API (skipped silently when unavailable)
     * @param namespace the model's asset namespace (the mob id's namespace)
     * @param modelPath the mob id's path segment, also the per-mob asset folder
     * @param model     the parsed model
     * @return the pieces per bone index; empty when nothing could be emitted
     */
    public static Result emit(PackApi pack, String namespace, String modelPath, ModelData model) {
        Map<Integer, List<Piece>> piecesByBone = new HashMap<>();
        List<String> repairs = new ArrayList<>();
        if (pack == null || !pack.isAvailable() || namespace == null || modelPath == null || model == null) {
            return new Result(piecesByBone, repairs);
        }

        List<TextureEntry> textures = prepareTextures(pack, namespace, modelPath, model, repairs);

        String folder = ROOT + modelPath + "/";
        for (int boneIndex = 0; boneIndex < model.boneCount(); boneIndex++) {
            ModelBone bone = model.bone(boneIndex);
            if (bone.cubes().isEmpty()) {
                continue;
            }
            List<PiecePlan> plans = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            split(bone.cubes(), 0, plans, problems);
            repairs.addAll(problems);
            for (int p = 0; p < plans.size(); p++) {
                PiecePlan plan = plans.get(p);
                String path = folder + bone.name() + (p == 0 ? "" : "_p" + (p + 1));
                byte[] json = GSON.toJson(boneModel(model, textures, plan))
                        .getBytes(StandardCharsets.UTF_8);
                BoneRigAssetWriter.registerGenerated(pack, namespace, path, json);
                Vector3f extra = new Vector3f(plan.offset).sub(ANCHOR_PX, ANCHOR_PX, ANCHOR_PX);
                piecesByBone.computeIfAbsent(boneIndex, k -> new ArrayList<>())
                        .add(new Piece(path, extra));
            }
        }
        return new Result(piecesByBone, repairs);
    }

    /** The item-model component path of one emitted piece. */
    public static String componentPath(String modelPath, String boneName, int piece) {
        return ROOT + modelPath + "/" + boneName + (piece == 0 ? "" : "_p" + (piece + 1));
    }

    // ------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------

    private record TextureEntry(String packName, String reference, int width, int height) {
    }

    /**
     * Registers every texture under {@code textures/item/mob/<modelPath>/} and
     * returns the sanitized entries the model JSON references. Missing or
     * undecodable textures are repaired with the neutral placeholder — a
     * missing texture would render as the client's magenta/black error cube,
     * which the pipeline never ships.
     */
    private static List<TextureEntry> prepareTextures(PackApi pack, String namespace, String modelPath,
                                                      ModelData model, List<String> repairs) {
        List<TextureEntry> entries = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        List<ModelTexture> textures = model.textures();
        for (int i = 0; i < textures.size(); i++) {
            ModelTexture texture = textures.get(i);
            String name = ModelBone.sanitizeName(texture.name(), i);
            String unique = name;
            int suffix = 2;
            while (!taken.add(unique)) {
                unique = name + "_" + suffix++;
            }
            byte[] png = texture.png();
            if (!PngUtil.isPng(png)) {
                png = PngUtil.neutralPlaceholder(texture.width(), texture.height());
                repairs.add("texture '" + texture.name() + "' was missing or undecodable;"
                        + " a neutral placeholder was substituted");
            } else {
                int[] dims = PngUtil.dimensions(png);
                if (dims == null) {
                    png = PngUtil.neutralPlaceholder(texture.width(), texture.height());
                    repairs.add("texture '" + texture.name() + "' has unreadable dimensions;"
                            + " a neutral placeholder was substituted");
                } else if (dims[0] != texture.width() || dims[1] != texture.height()) {
                    repairs.add("texture '" + texture.name() + "' is " + dims[0] + "x" + dims[1]
                            + " but the model declares " + texture.width() + "x" + texture.height()
                            + "; UVs follow the declared size");
                }
            }
            String path = "textures/item/" + PackModelEmitter.ROOT + modelPath + "/" + unique + ".png";
            pack.registerGeneratedAsset(namespace, path, png);
            entries.add(new TextureEntry(unique, namespace + ":item/" + ROOT + modelPath + "/" + unique,
                    texture.width(), texture.height()));
        }
        if (entries.isEmpty()) {
            // Geometry without any texture: without a repair the client would
            // draw the magenta/black error cube.
            byte[] png = PngUtil.neutralPlaceholder(16, 16);
            pack.registerGeneratedAsset(namespace, "textures/item/" + ROOT + modelPath + "/placeholder.png", png);
            entries.add(new TextureEntry("placeholder", namespace + ":item/" + ROOT + modelPath + "/placeholder",
                    16, 16));
            repairs.add("the model declares no textures; a neutral placeholder texture was added");
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Geometry planning (window fitting)
    // ------------------------------------------------------------------

    private record PiecePlan(List<ModelCube> boxes, Vector3f offset) {
    }

    private static void split(List<ModelCube> boxes, int depth, List<PiecePlan> out, List<String> problems) {
        float[] bounds = bounds(boxes);
        int axis = overflowingAxis(bounds);
        if (axis < 0) {
            out.add(new PiecePlan(boxes, anchorOffset(bounds)));
            return;
        }
        if (depth >= MAX_SPLIT_DEPTH || boxes.size() < 2) {
            // A single box longer than the window cannot be represented; centre
            // it and report rather than silently tearing it.
            out.add(new PiecePlan(boxes, centreOffset(bounds)));
            problems.add("a box spans more than the client's " + (int) WINDOW_SPAN
                    + "px element window on the " + "xyz".charAt(axis)
                    + " axis; it is centred and may be clipped by the client");
            return;
        }
        float splitAt = (bounds[axis * 2] + bounds[axis * 2 + 1]) / 2.0f;
        List<ModelCube> low = new ArrayList<>();
        List<ModelCube> high = new ArrayList<>();
        for (ModelCube box : boxes) {
            float mid = axis == 0 ? (box.fromX() + box.toX()) / 2.0f
                    : axis == 1 ? (box.fromY() + box.toY()) / 2.0f
                    : (box.fromZ() + box.toZ()) / 2.0f;
            if (mid < splitAt) {
                low.add(box);
            } else {
                high.add(box);
            }
        }
        if (low.isEmpty() || high.isEmpty()) {
            out.add(new PiecePlan(boxes, centreOffset(bounds)));
            problems.add("boxes overlap across the client's " + (int) WINDOW_SPAN
                    + "px element window on the " + "xyz".charAt(axis)
                    + " axis; the group is centred and may be clipped by the client");
            return;
        }
        split(low, depth + 1, out, problems);
        split(high, depth + 1, out, problems);
    }

    /** Emitted-space bounds: {@code {minX, maxX, minY, maxY, minZ, maxZ}} (y up). */
    private static float[] bounds(List<ModelCube> boxes) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (ModelCube box : boxes) {
            minX = Math.min(minX, box.fromX());
            maxX = Math.max(maxX, box.toX());
            minY = Math.min(minY, box.fromY());
            maxY = Math.max(maxY, box.toY());
            minZ = Math.min(minZ, box.fromZ());
            maxZ = Math.max(maxZ, box.toZ());
        }
        return new float[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    private static int overflowingAxis(float[] bounds) {
        for (int axis = 0; axis < 3; axis++) {
            if (bounds[axis * 2 + 1] - bounds[axis * 2] > WINDOW_SPAN) {
                return axis;
            }
        }
        return -1;
    }

    /** Per-axis {@code +8}px anchor plus the smallest extra in-window shift. */
    private static Vector3f anchorOffset(float[] bounds) {
        return new Vector3f(axisOffset(bounds[0], bounds[1]),
                axisOffset(bounds[2], bounds[3]),
                axisOffset(bounds[4], bounds[5]));
    }

    private static Vector3f centreOffset(float[] bounds) {
        return new Vector3f(centre(bounds[0], bounds[1]), centre(bounds[2], bounds[3]),
                centre(bounds[4], bounds[5]));
    }

    private static float centre(float lo, float hi) {
        return ((WINDOW_MIN + WINDOW_MAX) / 2.0f) - (lo + hi) / 2.0f;
    }

    private static float axisOffset(float lo, float hi) {
        float lower = WINDOW_MIN - (lo + ANCHOR_PX);
        float upper = WINDOW_MAX - (hi + ANCHOR_PX);
        float delta = 0.0f;
        if (delta < lower) {
            delta = lower;
        }
        if (delta > upper) {
            delta = upper;
        }
        return ANCHOR_PX + delta;
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    private static JsonObject boneModel(ModelData model, List<TextureEntry> textures, PiecePlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("credit", "Generated by Mineplus");
        root.addProperty("ambientocclusion", false);

        JsonObject textureMap = new JsonObject();
        for (int i = 0; i < textures.size(); i++) {
            textureMap.addProperty(String.valueOf(i), textures.get(i).reference());
        }
        if (textures.size() > 0) {
            textureMap.addProperty("particle", textures.get(0).reference());
        }
        root.add("textures", textureMap);

        JsonArray elements = new JsonArray();
        for (ModelCube cube : plan.boxes()) {
            elements.add(element(model, textures, cube, plan.offset()));
        }
        root.add("elements", elements);
        return root;
    }

    private static JsonObject element(ModelData model, List<TextureEntry> textures,
                                      ModelCube cube, Vector3f offset) {
        float ox = offset.x;
        float oy = offset.y;
        float oz = offset.z;
        float inflate = cube.inflate();
        float x1 = cube.fromX() - inflate + ox;
        float y1 = cube.fromY() - inflate + oy;
        float z1 = cube.fromZ() - inflate + oz;
        float x2 = cube.toX() + inflate + ox;
        float y2 = cube.toY() + inflate + oy;
        float z2 = cube.toZ() + inflate + oz;

        JsonObject element = new JsonObject();
        element.add("from", vec3(x1, y1, z1));
        element.add("to", vec3(x2, y2, z2));
        element.addProperty("shade", false);

        TextureEntry texture = textureFor(textures, cube.textureIndex(), model);
        int textureSlot = textures.indexOf(texture);
        if (textureSlot < 0) {
            textureSlot = 0;
        }
        float uScale = 16.0f / Math.max(1, texture.width());
        float vScale = 16.0f / Math.max(1, texture.height());
        JsonObject faces = new JsonObject();
        for (String direction : FACE_DIRECTIONS) {
            ModelCube.Uv uv = cube.face(direction);
            if (uv == null) {
                continue;
            }
            faces.add(direction, face(uv.u1() * uScale, uv.v1() * vScale,
                    uv.u2() * uScale, uv.v2() * vScale, textureSlot));
        }
        element.add("faces", faces);
        return element;
    }

    private static TextureEntry textureFor(List<TextureEntry> textures, int index, ModelData model) {
        if (index < 0 || index >= textures.size()) {
            // Fall back to the model's own resolution, matching how the parser
            // scaled box-UV pixels; index 0 keeps the cube textured at all.
            if (textures.isEmpty()) {
                return new TextureEntry("placeholder", "minecraft:block/light_gray_concrete", 16, 16);
            }
            TextureEntry first = textures.get(0);
            return new TextureEntry(first.packName(), first.reference(), model.textureWidth(),
                    model.textureHeight());
        }
        return textures.get(index);
    }

    private static JsonObject face(float u1, float v1, float u2, float v2, int textureSlot) {
        JsonArray uv = new JsonArray();
        uv.add(round(u1));
        uv.add(round(v1));
        uv.add(round(u2));
        uv.add(round(v2));
        JsonObject face = new JsonObject();
        face.add("uv", uv);
        face.addProperty("texture", "#" + textureSlot);
        return face;
    }

    private static float round(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }

    private static JsonArray vec3(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(round(x));
        array.add(round(y));
        array.add(round(z));
        return array;
    }
}
