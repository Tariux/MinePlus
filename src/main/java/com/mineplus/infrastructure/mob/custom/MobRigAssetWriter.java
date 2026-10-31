package com.mineplus.infrastructure.mob.custom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.rig.BoneRigAssetWriter;
import com.mineplus.infrastructure.virtual.rig.BoneRigDefinition;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/**
 * Compiles a {@link CustomMobModel} into pack assets: one PNG, one item model
 * per display piece, and one item definition per piece. The rig renderer then
 * presents each piece as an {@code ItemDisplay}.
 *
 * <p><b>Anchor contract.</b> A part's display sits at the part pivot, and the
 * client's item renderer translates every element by half a block before
 * drawing it. A piece's geometry is therefore authored as
 * {@code (q - pivot) + 8px} — the same convention the pack bone writer uses —
 * so the element origin maps exactly onto the display position and rotating
 * the display rotates the part about its pivot. Emitting absolute geometry or
 * re-centring a part's bounding box (which this writer used to do) displaces
 * every part from its pivot, which is what made parts land in the wrong place
 * and animate around the wrong point.</p>
 *
 * <p><b>Element window.</b> The client accepts element coordinates in
 * {@code -16..32} px on each axis (48 px of span). If an anchored part does not
 * fit, the part is split into contiguous groups along the overflowing axis and
 * each group receives the smallest additional in-window shift. Only that extra
 * shift is recorded in the blueprint ({@link BoneRigDefinition.GeometryPiece#offsetPx()})
 * and the renderer subtracts it, rotated with the piece, so a split part still
 * renders as one rigid piece of the model. Only a single box longer than the
 * window cannot be represented; it is centred and reported rather than
 * silently torn.</p>
 *
 * <p>UV values are texture pixels scaled into the vanilla {@code 0..16} model
 * window by {@code 16 / textureSize}; a box without authored per-face windows
 * uses the vanilla box-UV cross layout.</p>
 */
public final class MobRigAssetWriter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Namespace-relative folder every generated mob rig asset lives under. */
    private static final String ROOT = "entity/";

    /**
     * The client's element window: valid coordinates are {@code -16 .. 32}
     * pixels on each axis.
     */
    private static final float WINDOW_MIN = -16.0f;
    private static final float WINDOW_MAX = 32.0f;
    private static final float WINDOW_SPAN = WINDOW_MAX - WINDOW_MIN; // 48

    /**
     * Half a block in pixels: the client draws element space translated by
     * {@code (-0.5, -0.5, -0.5)} block, so pivot-relative geometry is authored
     * half a block off to land on the display position.
     */
    private static final float ITEM_RENDER_OFFSET_PX = 8.0f;

    /** Guard against pathological models; a real part needs one or two cuts. */
    private static final int MAX_SPLIT_DEPTH = 6;

    private MobRigAssetWriter() {
    }

    /**
     * Registers every asset for one model.
     *
     * @param pack      the pack API (no-op when unavailable)
     * @param namespace asset namespace (the mob id's namespace)
     * @param idPath    the mob id's path, also the per-mob asset folder
     * @param model     the compiled model
     */
    public static void register(PackApi pack, String namespace, String idPath, CustomMobModel model) {
        if (pack == null || !pack.isAvailable() || model == null) {
            return;
        }
        String folder = ROOT + idPath + "/";
        String texturePath = folder + model.textureName();
        pack.registerGeneratedAsset(namespace, "textures/item/" + texturePath + ".png", model.texturePng());
        String textureReference = namespace + ":item/" + texturePath;
        for (MobPart part : model.parts()) {
            if (part.boxes().isEmpty()) {
                continue;
            }
            List<Piece> pieces = geometryPlan(part, idPath, false);
            for (Piece piece : pieces) {
                byte[] json = GSON.toJson(partModel(model, piece, textureReference))
                        .getBytes(StandardCharsets.UTF_8);
                BoneRigAssetWriter.registerGenerated(pack, namespace, piece.path(), json);
            }
        }
    }

    /**
     * The display pieces of one part: one per contiguous geometry group. The
     * renderer builds its blueprint from the same call, so the model paths and
     * the in-window shifts can never disagree between the writer and the rig.
     *
     * <p>{@link BoneRigDefinition.GeometryPiece#offsetPx()} carries only the
     * <em>extra</em> shift a piece needed beyond the standard half-block
     * ({@value #ITEM_RENDER_OFFSET_PX}px) anchor — the part of the authored
     * offset the renderer has to compensate for. The constant anchor is
     * already consumed by the client's own item translation, so compensating
     * for it a second time would displace the piece by half a block.</p>
     *
     * @param part          the part to plan
     * @param idPath        the mob id's path
     * @param reportSplits  log when a part needed more than one piece
     */
    public static List<BoneRigDefinition.GeometryPiece> geometryPieces(MobPart part, String idPath,
                                                                      boolean reportSplits) {
        List<BoneRigDefinition.GeometryPiece> result = new ArrayList<>();
        if (part == null || part.boxes().isEmpty()) {
            return result;
        }
        for (Piece piece : geometryPlan(part, idPath, reportSplits)) {
            Vector3f extra = new Vector3f(piece.offset())
                    .sub(ITEM_RENDER_OFFSET_PX, ITEM_RENDER_OFFSET_PX, ITEM_RENDER_OFFSET_PX);
            result.add(new BoneRigDefinition.GeometryPiece(piece.path(), extra));
        }
        return result;
    }

    /**
     * The {@code item_model} component path of one part — the value the rig
     * renderer puts on the display stack (slashes are allowed in the
     * component but not in an item id, which is why the rig does not use
     * {@code ItemModelAsset}).
     */
    public static String componentPath(String idPath, String partName) {
        return ROOT + idPath + "/" + partName;
    }

    /** The model resource reference of one part ({@code <ns>:item/entity/<mob>/<part>}). */
    public static String modelReference(String namespace, String idPath, String partName) {
        return namespace + ":item/" + componentPath(idPath, partName);
    }

    // ------------------------------------------------------------------
    // Geometry planning
    // ------------------------------------------------------------------

    private record Piece(String path, Vector3f offset, List<MobBox> boxes) {
    }

    /**
     * Plans the pieces of one part: group boxes until each group fits the
     * client window, then give every group its anchor offset and a stable path.
     */
    private static List<Piece> geometryPlan(MobPart part, String idPath, boolean reportSplits) {
        List<List<MobBox>> groups = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        split(part.boxes(), 0, part.name(), groups, problems);

        String basePath = componentPath(idPath, part.name());
        List<Piece> pieces = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            List<MobBox> boxes = groups.get(index);
            float[] bounds = emittedBounds(boxes);
            Vector3f offset = anchorOffset(bounds);
            String path = index == 0
                    ? basePath
                    : componentPath(idPath, part.name() + "_p" + (index + 1));
            pieces.add(new Piece(path, offset, boxes));
        }
        if (!problems.isEmpty()) {
            DebugLogger.warning("[CustomMobs] Part '" + part.name() + "' of mob '" + idPath
                    + "': " + String.join("; ", problems));
        } else if (reportSplits && pieces.size() > 1) {
            DebugLogger.info("[CustomMobs] Part '" + part.name() + "' of mob '" + idPath + "' spans more"
                    + " than the client's 48px element window and renders as " + pieces.size()
                    + " display piece(s).");
        }
        return pieces;
    }

    private static void split(List<MobBox> boxes, int depth, String partName,
                              List<List<MobBox>> out, List<String> problems) {
        float[] bounds = emittedBounds(boxes);
        int axis = overflowingAxis(bounds);
        if (axis < 0) {
            out.add(boxes);
            return;
        }
        if (depth >= MAX_SPLIT_DEPTH || boxes.size() < 2) {
            out.add(boxes);
            problems.add("a box spans more than the client's " + (int) WINDOW_SPAN
                    + "px element window on the " + "xyz".charAt(axis)
                    + " axis; it is centred and may be clipped by the client");
            return;
        }
        float splitAt = axis == 0 ? (bounds[0] + bounds[1]) / 2.0f
                : axis == 1 ? (bounds[2] + bounds[3]) / 2.0f
                : (bounds[4] + bounds[5]) / 2.0f;
        List<MobBox> low = new ArrayList<>();
        List<MobBox> high = new ArrayList<>();
        for (MobBox box : boxes) {
            float mid = axis == 0 ? (box.fromX() + box.toX()) / 2.0f
                    : axis == 1 ? -(box.fromY() + box.toY()) / 2.0f
                    : (box.fromZ() + box.toZ()) / 2.0f;
            if (mid < splitAt) {
                low.add(box);
            } else {
                high.add(box);
            }
        }
        if (low.isEmpty() || high.isEmpty()) {
            out.add(boxes);
            problems.add("a box spans more than the client's " + (int) WINDOW_SPAN
                    + "px element window on the " + "xyz".charAt(axis)
                    + " axis; it is centred and may be clipped by the client");
            return;
        }
        split(low, depth + 1, partName, out, problems);
        split(high, depth + 1, partName, out, problems);
    }

    /** Index (0=x, 1=y, 2=z) of the first axis whose emitted span exceeds the window, or {@code -1}. */
    private static int overflowingAxis(float[] bounds) {
        if (bounds[1] - bounds[0] > WINDOW_SPAN) {
            return 0;
        }
        if (bounds[3] - bounds[2] > WINDOW_SPAN) {
            return 1;
        }
        if (bounds[5] - bounds[4] > WINDOW_SPAN) {
            return 2;
        }
        return -1;
    }

    /**
     * Emitted-space bounds of a box list: {@code {minX, maxX, minY, maxY,
     * minZ, maxZ}} where y is up ({@code element} writes {@code -fromY}).
     */
    private static float[] emittedBounds(List<MobBox> boxes) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (MobBox box : boxes) {
            minX = Math.min(minX, box.fromX());
            maxX = Math.max(maxX, box.toX());
            minY = Math.min(minY, -box.toY());
            maxY = Math.max(maxY, -box.fromY());
            minZ = Math.min(minZ, box.fromZ());
            maxZ = Math.max(maxZ, box.toZ());
        }
        return new float[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    /**
     * The per-axis shift that anchors the piece on its pivot (constant
     * {@code +8}px) plus the smallest extra shift that brings every coordinate
     * into the client window. A piece that still cannot fit is centred, and the
     * splitter has already reported it.
     */
    private static Vector3f anchorOffset(float[] bounds) {
        return new Vector3f(
                axisOffset(bounds[0], bounds[1]),
                axisOffset(bounds[2], bounds[3]),
                axisOffset(bounds[4], bounds[5]));
    }

    private static float axisOffset(float lo, float hi) {
        float lower = WINDOW_MIN - (lo + ITEM_RENDER_OFFSET_PX);
        float upper = WINDOW_MAX - (hi + ITEM_RENDER_OFFSET_PX);
        if (lower > upper) {
            float windowCenter = (WINDOW_MIN + WINDOW_MAX) / 2.0f;
            return windowCenter - (lo + hi) / 2.0f;
        }
        float delta = 0.0f;
        if (delta < lower) {
            delta = lower;
        }
        if (delta > upper) {
            delta = upper;
        }
        return ITEM_RENDER_OFFSET_PX + delta;
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    private static JsonObject partModel(CustomMobModel model, Piece piece, String textureReference) {
        JsonObject root = new JsonObject();
        root.addProperty("credit", "Generated by Mineplus");
        root.addProperty("ambientocclusion", false);
        JsonObject textures = new JsonObject();
        textures.addProperty("0", textureReference);
        textures.addProperty("particle", textureReference);
        root.add("textures", textures);

        JsonArray elements = new JsonArray();
        for (MobBox box : piece.boxes()) {
            elements.add(element(model, box, piece.offset()));
        }
        root.add("elements", elements);
        return root;
    }

    private static JsonObject element(CustomMobModel model, MobBox box, Vector3f offset) {
        float ox = offset.x;
        float oy = offset.y;
        float oz = offset.z;
        float x1 = box.fromX() + ox;
        float x2 = box.toX() + ox;
        float y1 = -box.toY() + oy;
        float y2 = -box.fromY() + oy;
        float z1 = box.fromZ() + oz;
        float z2 = box.toZ() + oz;

        JsonObject element = new JsonObject();
        element.add("from", vec3(x1, y1, z1));
        element.add("to", vec3(x2, y2, z2));
        element.addProperty("shade", false);

        if (box.hasRotation()) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", vec3(
                    box.rotationOriginX() + ox,
                    box.rotationOriginY() + oy,
                    box.rotationOriginZ() + oz));
            rotation.addProperty("axis", String.valueOf(box.rotationAxis()));
            rotation.addProperty("angle", round(box.rotationAngle()));
            rotation.addProperty("rescale", false);
            element.add("rotation", rotation);
        }

        JsonObject faces = new JsonObject();
        if (box.hasFaceUvs()) {
            for (String direction : FACE_DIRECTIONS) {
                MobFaceUvs.Uv uv = box.faceUvs().forDirection(direction);
                if (uv != null) {
                    face(faces, model, direction, uv.u1(), uv.v1(), uv.u2(), uv.v2());
                }
            }
        } else {
            float u = box.textureU();
            float v = box.textureV();
            float w = box.width();
            float h = box.height();
            float d = box.depth();
            face(faces, model, "east", u, v + d, u + d, v + d + h);
            face(faces, model, "north", u + d, v + d, u + d + w, v + d + h);
            face(faces, model, "west", u + d + w, v + d, u + 2 * d + w, v + d + h);
            face(faces, model, "south", u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h);
            face(faces, model, "up", u + d + w, v + d, u + d, v);
            face(faces, model, "down", u + d + 2 * w, v, u + d + w, v + d);
        }
        element.add("faces", faces);
        return element;
    }

    private static final String[] FACE_DIRECTIONS = {"north", "south", "east", "west", "up", "down"};

    private static void face(JsonObject faces, CustomMobModel model, String direction,
                             float u1, float v1, float u2, float v2) {
        float uScale = 16.0f / Math.max(1, model.textureWidth());
        float vScale = 16.0f / Math.max(1, model.textureHeight());
        JsonArray uv = new JsonArray();
        uv.add(round(u1 * uScale));
        uv.add(round(v1 * vScale));
        uv.add(round(u2 * uScale));
        uv.add(round(v2 * vScale));
        JsonObject face = new JsonObject();
        face.add("uv", uv);
        face.addProperty("texture", "#0");
        faces.add(direction, face);
    }

    private static JsonArray vec3(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(round(x));
        array.add(round(y));
        array.add(round(z));
        return array;
    }

    private static float round(float value) {
        return Math.round(value * 1000.0f) / 1000.0f;
    }
}
