package com.mineplus.pack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelTexture;
import com.mineplus.pack.asset.TextureAsset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the animation a Blockbench project already contains into posed Java
 * item models — the frame set a framed item ({@link ItemFrames}) plays.
 *
 * <p>An item model has no bones: one model is one static list of boxes plus one
 * {@code display} block per context. A Blockbench model, on the other hand, is a
 * bone tree with hinge rotations and clip curves, which is how an author actually
 * animates a gun — a lever that folds, a hammer that drops, a scope that slides
 * into the eye. Hand-baking that into forty model files is how the motion gets
 * lost, so this class does it deterministically instead:</p>
 *
 * <ul>
 *   <li><b>the whole-model pose goes to {@code display}.</b> The outermost animated
 *       bone — the group every other animated bone and every cube hangs on — is
 *       folded into the item's per-context display transform. A display rotation
 *       is unbounded and applies about the model's centre, so a recoil that lifts
 *       a rifle 30° is expressed exactly, in first and third person, while the
 *       gui, ground and fixed poses keep the author's framing;</li>
 *   <li><b>a part's hinge goes to its elements.</b> Every other bone's sampled
 *       rotation and shift is composed into one rigid transform per cube and
 *       expressed as the element's own {@code rotation} (about the solved pivot)
 *       plus an axial shift — exact for any single-axis hinge, which is what a
 *       lever, bolt, hammer or charging handle is;</li>
 *   <li><b>what cannot be expressed is reported, not hidden.</b> A cube whose
 *       remaining transform rotates about more than one axis has no legal element
 *       rotation; its posed box is emitted as the axis-aligned envelope of the
 *       rotated corners and one issue names the bone and clip, so the author sees
 *       the one part the item-model format cannot carry. A hinge past ±45° keeps
 *       its authored angle in the file and earns an issue of its own, because a
 *       client that clamps element rotations will stop it short.</li>
 * </ul>
 *
 * <p>Sampling follows the Blockbench timeline, not a rig's playhead: a channel
 * the playhead has not reached contributes nothing, so a clip whose first key sits
 * at a quarter second genuinely starts from the rest pose
 * ({@link ModelAnimation.Curve#sampleFromRest}). Translations and pivots are model
 * pixels, rotations degrees, in the y-up space {@link ModelData} documents.</p>
 *
 * <p>This class is a compiler, not a content owner: it never names a frame, picks
 * a clip's role or writes a file. A module decides that {@code fire} is
 * {@code <id>_fire_0..n} and a scope clip is an in/hold/out triple, because that
 * vocabulary belongs to the gameplay runtime that reads it.</p>
 */
public final class BbClipBaker {

    /** The contexts a held item's own motion is allowed to change. */
    private static final String[] HELD_CONTEXTS = {
            "firstperson_righthand", "firstperson_lefthand",
            "thirdperson_righthand", "thirdperson_lefthand"
    };
    private static final float CENTRE = 8.0f;
    private static final float MAX_ELEMENT_ANGLE = 45.0f;
    private static final String[] FACE_NAMES = {"north", "east", "south", "west", "up", "down"};
    private static final String[] AXIS_NAMES = {"x", "y", "z"};

    /**
     * How the rest pose sits in a hand, and what it is drawn with.
     *
     * @param display          the authored {@code display} block of the item model
     *                         (context → rotation/translation/scale); the rest pose
     *                         of the model, which the clip poses are folded onto
     * @param textureReference the pack path every face is bound to, e.g.
     *                         {@code mineplusgun:item/frontier_eye}
     * @param frontLight       true to emit {@code gui_light: front}, which keeps a
     *                         boxy prop evenly lit in an inventory GUI
     * @param textureResolver  maps a parsed texture name to the pack reference its
     *                         sprite is registered under, or {@code null} to derive
     *                         every reference from {@code textureReference}'s prefix
     *                         plus the texture's own name. Modules whose flat texture
     *                         registration keys sprites by a folder id pass
     *                         {@code name -> prefix + "/" + folderId} so baked frames
     *                         reference exactly the registered path
     */
    public record Style(JsonObject display, String textureReference, boolean frontLight,
                        java.util.function.UnaryOperator<String> textureResolver,
                        HeldCorrection heldCorrection) {

        public Style {
            display = display == null ? new JsonObject() : display.deepCopy();
            textureReference = textureReference == null ? "" : textureReference.trim().toLowerCase(Locale.ROOT);
            heldCorrection = heldCorrection == null ? HeldCorrection.NONE : heldCorrection;
            display = heldCorrection.apply(display);
        }

        /** Convenience constructor for the prefix-derivation default. */
        public Style(JsonObject display, String textureReference, boolean frontLight) {
            this(display, textureReference, frontLight, null, null);
        }

        /** Convenience constructor with a texture resolver and no pose fix. */
        public Style(JsonObject display, String textureReference, boolean frontLight,
                     java.util.function.UnaryOperator<String> textureResolver) {
            this(display, textureReference, frontLight, textureResolver, null);
        }

        /**
         * A held-context pose fix applied on top of whatever display block the
         * style carries (authored, baked or the module's default table). The
         * gun geometry the baker sees runs along model +Z, which in the vanilla
         * hand contexts points back at the player, so a module whose Blockbench
         * source was authored without a display section renders its barrel
         * backwards; {@code yawDegrees} rotates the four held contexts (180
         * points a +Z barrel forward) and {@code yLiftPixels} is subtracted
         * from their translation Y to drop a model that rides high. NONE keeps
         * the authored pose untouched.
         */
        public record HeldCorrection(float yawDegrees, float yLiftPixels) {

            public static final HeldCorrection NONE = new HeldCorrection(0.0f, 0.0f);

            public boolean isNone() {
                return yawDegrees == 0.0f && yLiftPixels == 0.0f;
            }

            private JsonObject apply(JsonObject display) {
                if (isNone()) {
                    return display;
                }
                for (String context : HELD_CONTEXTS) {
                    if (!display.has(context) || !display.get(context).isJsonObject()) {
                        continue;
                    }
                    JsonObject transform = display.getAsJsonObject(context).deepCopy();
                    if (transform.has("rotation") && transform.get("rotation").isJsonArray()) {
                        addY(transform, "rotation", yawDegrees);
                    }
                    if (transform.has("translation") && transform.get("translation").isJsonArray()) {
                        addY(transform, "translation", -yLiftPixels);
                    }
                    display.add(context, transform);
                }
                return display;
            }

            private static void addY(JsonObject transform, String key, float deltaY) {
                JsonArray axis = transform.getAsJsonArray(key).deepCopy();
                while (axis.size() < 3) {
                    axis.add(0.0f);
                }
                axis.set(1, new com.google.gson.JsonPrimitive(
                        Math.round((axis.get(1).getAsFloat() + deltaY) * 1000.0f) / 1000.0f));
                transform.add(key, axis);
            }
        }
    }

    /**
     * The baked models, in the order they were requested, plus what the author
     * should know about the source.
     */
    public record Result(List<JsonObject> models, List<String> issues) {

        public Result {
            models = List.copyOf(models);
            issues = List.copyOf(issues);
        }

        public boolean isEmpty() {
            return models.isEmpty();
        }
    }

    private BbClipBaker() {
    }

    // ------------------------------------------------------------------
    // Public surface
    // ------------------------------------------------------------------

    /** The clips of a parsed model that carry at least one bone curve. */
    public static List<String> clips(ModelData model) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, ModelAnimation> entry : model.animations().entrySet()) {
            if (!entry.getValue().trackEntries().isEmpty()) {
                names.add(entry.getKey());
            }
        }
        names.sort(null);
        return names;
    }

    /** Seconds one clip runs, or {@code 0} when the model has no such clip. */
    public static float lengthSeconds(ModelData model, String clip) {
        ModelAnimation animation = animation(model, clip);
        return animation == null ? 0.0f : animation.lengthSeconds();
    }

    /** The rest (unposed) item model of a parsed Blockbench model. */
    public static Result rest(ModelData model, Style style) {
        List<String> issues = new ArrayList<>();
        JsonObject pose = poseAt(model, style, null, 0.0f, issues, new LinkedHashSet<>());
        return new Result(pose == null ? List.of() : List.of(pose), issues);
    }

    /**
     * The posed item models of one clip, evenly sampled across its length in play
     * order — first the clip's own opening pose, last the pose it ends in.
     *
     * @param frames how many poses to sample; {@code 1} is the clip's end pose
     */
    public static Result poses(ModelData model, Style style, String clip, int frames) {
        ModelAnimation animation = animation(model, clip);
        if (animation == null) {
            return new Result(List.of(), List.of("no clip named '" + clip + "' in " + model.sourceName()));
        }
        int count = Math.max(1, frames);
        float length = animation.lengthSeconds();
        List<String> issues = new ArrayList<>();
        Set<String> warned = new LinkedHashSet<>();
        List<JsonObject> out = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            float time = count == 1 || length <= 0.0f
                    ? (index == 0 ? 0.0f : length)
                    : length * index / (float) (count - 1);
            JsonObject pose = poseAt(model, style, animation, time, issues, warned);
            if (pose != null) {
                out.add(pose);
            }
        }
        return new Result(out, issues);
    }

    // ------------------------------------------------------------------
    // One posed model
    // ------------------------------------------------------------------

    private static JsonObject poseAt(ModelData model, Style style, ModelAnimation animation, float time,
                                     List<String> issues, Set<String> warned) {
        List<ModelBone> bones = model.bones();
        Map<Integer, BoneDelta> deltas = sample(bones, animation, time, issues, warned);
        int fold = foldBone(bones, animation, deltas);
        float[] uvScale = uvScale(model);
        JsonObject textureMap = textures(style, model);

        JsonObject root = new JsonObject();
        root.add("textures", textureMap);

        JsonArray elements = new JsonArray();
        for (int bone = 0; bone < bones.size(); bone++) {
            ModelBone holder = bones.get(bone);
            if (holder.cubes().isEmpty()) {
                continue;
            }
            List<Integer> chain = chain(bones, bone);
            for (int cubeIndex = 0; cubeIndex < holder.cubes().size(); cubeIndex++) {
                ModelCube cube = holder.cubes().get(cubeIndex);
                float[] pivot = pivot(holder);
                // A cube is stored relative to its own bone's pivot, so its rest
                // box in model space is that offset plus the pivot.
                float[] from = {cube.fromX() + pivot[0], cube.fromY() + pivot[1], cube.fromZ() + pivot[2]};
                float[] to = {cube.toX() + pivot[0], cube.toY() + pivot[1], cube.toZ() + pivot[2]};
                Transform posed = chainTransform(bones, chain, deltas, fold);
                elements.add(element(cube, name(holder, cubeIndex), from, to, posed, style,
                        holder, uvScale, issues, warned));
            }
        }
        if (elements.size() == 0) {
            issues.add(model.sourceName() + ": no cube geometry to pose.");
            return null;
        }
        root.add("elements", elements);
        JsonObject displayJson = display(style, bones, fold, deltas, model.sourceName(), issues, warned);
        autoHeldPose(root, displayJson, model.sourceName(), bones, issues);
        fitWindow(root, displayJson, model.sourceName(), issues);
        root.add("display", displayJson);
        if (style.frontLight()) {
            root.addProperty("gui_light", "front");
        }
        return root;
    }

    // ------------------------------------------------------------------
    // Window fitting
    // ------------------------------------------------------------------

    /**
     * Fills the four hand contexts from the model's own geometry when the
     * source authors none of them — see {@link HeldPoseSolver} for the
     * derivation. The applied pose is reported as an issue so an author can
     * see it happened and replace it with a hand-tuned display block; an
     * authored hand context on any of the four cancels the pass outright
     * (display data always wins).
     */
    private static void autoHeldPose(JsonObject root, JsonObject display, String sourceName,
                                     List<ModelBone> bones, List<String> issues) {
        for (String context : HELD_CONTEXTS) {
            if (object(display, context) != null) {
                return;
            }
        }
        HeldPoseSolver.Pose pose = HeldPoseSolver.solve(bones);
        if (pose == null) {
            return;
        }
        setHandContext(display, "firstperson_righthand",
                pose.firstpersonRotationRight(), pose.firstpersonTranslationRight(), 0.68f);
        setHandContext(display, "firstperson_lefthand",
                pose.firstpersonRotationLeft(), pose.firstpersonTranslationLeft(), 0.68f);
        setHandContext(display, "thirdperson_righthand",
                pose.thirdpersonRotationRight(), pose.thirdpersonTranslationRight(), 0.85f);
        setHandContext(display, "thirdperson_lefthand",
                pose.thirdpersonRotationLeft(), pose.thirdpersonTranslationLeft(), 0.85f);
        issues.add(sourceName + ": no hand display contexts were authored; a held"
                + " pose was derived from the model's geometry (principal-axis grip"
                + " alignment). Author a display block to replace it.");
    }

    /** Writes one hand context in the vanilla {rotation, translation, scale} shape. */
    private static void setHandContext(JsonObject display, String context,
                                       float[] rotation, float[] translation, float scale) {
        JsonObject transform = new JsonObject();
        addPair(transform, "rotation", rotation);
        addPair(transform, "translation", translation);
        addPair(transform, "scale", new float[]{scale, scale, scale});
        display.add(context, transform);
    }

    /** Every display context a vanilla item model carries. */
    private static final String[] DISPLAY_CONTEXTS = {
            "thirdperson_righthand", "thirdperson_lefthand",
            "firstperson_righthand", "firstperson_lefthand",
            "head", "gui", "ground", "fixed"
    };

    private static boolean isKnownDisplayContext(String key) {
        for (String context : DISPLAY_CONTEXTS) {
            if (context.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static final float WINDOW_MIN = -16.0f;
    private static final float WINDOW_MAX = 32.0f;

    /**
     * Fits the posed geometry into the client's {@code -16..32} px element
     * window. A real-scale gun easily exceeds the 48 px window on its long
     * axis; the client then refuses the whole model and renders the missing-
     * model cube — the "giant purple-black box".
     *
     * <p>The fit is a uniform scale about the element-space centre plus a
     * re-centre translation; the display transform compensates exactly
     * ({@code scale × 1/f}, translation minus {@code R·S·w/f}), so the gun
     * still presents at its authored world size. Runs after the display fold,
     * so both authored and animated contexts are corrected.</p>
     */
    private static void fitWindow(JsonObject root, JsonObject display, String sourceName,
                                  List<String> issues) {
        JsonArray elements = root.getAsJsonArray("elements");
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (JsonElement entry : elements) {
            JsonObject element = entry.getAsJsonObject();
            widen(bounds(element, "from"), min, max);
            widen(bounds(element, "to"), min, max);
        }
        float scale = 1.0f;
        boolean overflow = false;
        for (int axis = 0; axis < 3; axis++) {
            if (min[axis] < WINDOW_MIN - 0.01f || max[axis] > WINDOW_MAX + 0.01f) {
                overflow = true;
            }
            float span = max[axis] - min[axis];
            if (span > WINDOW_MAX - WINDOW_MIN) {
                scale = Math.min(scale, (WINDOW_MAX - WINDOW_MIN) / span);
            }
        }
        if (!overflow) {
            return;
        }
        float[] shift = new float[3];
        float[] correction = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            float centre = (min[axis] + max[axis]) / 2.0f;
            shift[axis] = CENTRE - scale * centre;
            // The translation term the display must absorb on top of the
            // scale division: w = t - (1-f)·c, in element pixels.
            correction[axis] = (shift[axis] - (1.0f - scale) * CENTRE) / scale;
        }
        for (JsonElement entry : elements) {
            JsonObject element = entry.getAsJsonObject();
            moveCorner(element, "from", scale, shift);
            moveCorner(element, "to", scale, shift);
        }
        for (String context : DISPLAY_CONTEXTS) {
            JsonObject transform = object(display, context);
            if (transform == null) {
                transform = new JsonObject();
                display.add(context, transform);
            }
            float[] rotation = triple(transform, "rotation", new float[3]);
            float[] translation = triple(transform, "translation", new float[3]);
            float[] s = triple(transform, "scale", new float[]{1.0f, 1.0f, 1.0f});
            float[] matrix = eulerXYZ(rotation[0], rotation[1], rotation[2]);
            float[] absorbed = apply(matrix, new float[]{
                    s[0] == 0.0f ? 0.0f : s[0] * correction[0],
                    s[1] == 0.0f ? 0.0f : s[1] * correction[1],
                    s[2] == 0.0f ? 0.0f : s[2] * correction[2]});
            addPair(transform, "translation", new float[]{
                    translation[0] - absorbed[0],
                    translation[1] - absorbed[1],
                    translation[2] - absorbed[2]});
            addPair(transform, "scale", new float[]{
                    s[0] / scale, s[1] / scale, s[2] / scale});
        }
        issues.add(sourceName + ": posed geometry exceeds the client's "
                + (int) (WINDOW_MAX - WINDOW_MIN) + "px element window; it was scaled x"
                + round(scale) + " and re-centred, with the display transform compensating"
                + " so the presented size is unchanged.");
    }

    /** Reads one element corner, defaulting to the origin. */
    private static float[] bounds(JsonObject element, String key) {
        JsonArray corner = element.getAsJsonArray(key);
        float[] out = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            out[axis] = corner.get(axis).getAsFloat();
        }
        return out;
    }

    private static void widen(float[] point, float[] min, float[] max) {
        for (int axis = 0; axis < 3; axis++) {
            min[axis] = Math.min(min[axis], point[axis]);
            max[axis] = Math.max(max[axis], point[axis]);
        }
    }

    private static void moveCorner(JsonObject element, String key, float scale, float[] shift) {
        JsonArray corner = element.getAsJsonArray(key);
        for (int axis = 0; axis < 3; axis++) {
            corner.set(axis, new JsonPrimitive(round(scale * corner.get(axis).getAsFloat() + shift[axis])));
        }
    }

    /** Every animated bone's sampled rotation and shift, keyed by bone index. */
    private static Map<Integer, BoneDelta> sample(List<ModelBone> bones, ModelAnimation animation, float time,
                                                 List<String> issues, Set<String> warned) {
        Map<Integer, BoneDelta> out = new HashMap<>();
        if (animation == null) {
            return out;
        }
        for (int index = 0; index < bones.size(); index++) {
            ModelBone bone = bones.get(index);
            ModelAnimation.BoneTrack track = animation.track(bone.name());
            if (track == null) {
                continue;
            }
            float[] scratch = new float[3];
            float[] rotation = new float[3];
            float[] position = new float[3];
            if (track.rotation() != null) {
                track.rotation().sampleFromRest(time, scratch);
                System.arraycopy(scratch, 0, rotation, 0, 3);
            }
            if (track.position() != null) {
                track.position().sampleFromRest(time, scratch);
                System.arraycopy(scratch, 0, position, 0, 3);
            }
            if (track.scale() != null && warned.add("scale:" + bone.name())) {
                issues.add("'" + bone.rawName() + "' is scaled by clip '" + animation.name()
                        + "'; an item model has no scale channel, so the scale curve is ignored.");
            }
            out.put(index, new BoneDelta(rotation, position));
        }
        return out;
    }

    /**
     * The animated bone whose motion is the whole model's pose. It has to be the
     * ancestor of every other animated bone and of every cube, because folding it
     * into {@code display} moves the entire item; anything else keeps its own
     * hinge as an element rotation.
     */
    private static int foldBone(List<ModelBone> bones, ModelAnimation animation,
                                Map<Integer, BoneDelta> deltas) {
        if (deltas.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestDepth = Integer.MAX_VALUE;
        for (Integer bone : deltas.keySet()) {
            int depth = depth(bones, bone);
            if (depth < bestDepth) {
                bestDepth = depth;
                best = bone;
            }
        }
        for (Integer bone : deltas.keySet()) {
            if (bone != best && !isAncestor(bones, best, bone)) {
                return -1;
            }
        }
        for (int index = 0; index < bones.size(); index++) {
            if (!bones.get(index).cubes().isEmpty()
                    && index != best && !isAncestor(bones, best, index)) {
                return -1;
            }
        }
        return best;
    }

    /** The rigid transform one cube carries: {@code p -> matrix * p + shift}. */
    private static final class Transform {
        float[] matrix = identity();
        float[] shift = new float[3];
    }

    private static Transform chainTransform(List<ModelBone> bones, List<Integer> chain,
                                            Map<Integer, BoneDelta> deltas, int fold) {
        Transform out = new Transform();
        for (int bone : chain) {
            ModelBone holder = bones.get(bone);
            float[] rest = blockbench(holder.restRotationX(), holder.restRotationY(), holder.restRotationZ());
            float[] rotation = rest;
            float[] position = new float[3];
            BoneDelta delta = bone == fold ? null : deltas.get(bone);
            if (delta != null) {
                float[] animated = blockbench(delta.rotation[0], delta.rotation[1], delta.rotation[2]);
                rotation = multiply(animated, rest);
                position = delta.position;
            }
            float[] pivot = pivot(holder);
            // T(pivot)·R·T(-pivot) plus the curve's own shift, composed outside
            // whatever the ancestors already did to this bone.
            float[] offset = new float[]{
                    pivot[0] - apply(rotation, pivot)[0] + position[0],
                    pivot[1] - apply(rotation, pivot)[1] + position[1],
                    pivot[2] - apply(rotation, pivot)[2] + position[2]
            };
            out.shift = add(apply(rotation, out.shift), offset);
            out.matrix = multiply(rotation, out.matrix);
        }
        return out;
    }

    private static JsonObject element(ModelCube cube, String name, float[] from, float[] to,
                                      Transform posed, Style style, ModelBone bone, float[] uvScale,
                                      List<String> issues, Set<String> warned) {
        JsonObject element = new JsonObject();
        element.addProperty("name", name);
        float[] shift = posed.shift;
        if (isIdentity(posed.matrix)) {
            addPair(element, "from", new float[]{from[0] + shift[0], from[1] + shift[1], from[2] + shift[2]});
            addPair(element, "to", new float[]{to[0] + shift[0], to[1] + shift[1], to[2] + shift[2]});
        } else {
            int axis = rotationAxis(posed.matrix);
            if (axis >= 0) {
                float[] unit = new float[]{axis == 0 ? 1.0f : 0.0f, axis == 1 ? 1.0f : 0.0f, axis == 2 ? 1.0f : 0.0f};
                float along = shift[0] * unit[0] + shift[1] * unit[1] + shift[2] * unit[2];
                float[] axial = {unit[0] * along, unit[1] * along, unit[2] * along};
                float[] plane = {shift[0] - axial[0], shift[1] - axial[1], shift[2] - axial[2]};
                float[] origin = pivotFor(posed.matrix, plane, axis);
                float[] shiftedFrom = {from[0] + axial[0], from[1] + axial[1], from[2] + axial[2]};
                float[] shiftedTo = {to[0] + axial[0], to[1] + axial[1], to[2] + axial[2]};
                // A rotation axis is a line, so the coordinate along it is free:
                // the box's own centre keeps the emitted origin where an author
                // reading the file would expect it.
                origin[axis] = (shiftedFrom[axis] + shiftedTo[axis]) / 2.0f;
                addPair(element, "from", shiftedFrom);
                addPair(element, "to", shiftedTo);
                float angle = rotationAngle(posed.matrix, axis);
                JsonObject rotation = new JsonObject();
                addPair(rotation, "origin", origin);
                rotation.addProperty("axis", AXIS_NAMES[axis]);
                // Clamped into the documented ±45 window: vanilla's model
                // deserializer REJECTS an out-of-range element angle, and one
                // refused hinge refuses the whole model — the gun rendered as
                // a missing-model cube. A clamp shortens the hinge visually
                // (the hammer stops at 45° instead of 66°) but the frame
                // always loads; the authored angle is reported as an issue.
                float clamped = Math.max(-MAX_ELEMENT_ANGLE, Math.min(MAX_ELEMENT_ANGLE, angle));
                rotation.addProperty("angle", round(clamped));
                element.add("rotation", rotation);
                if (Math.abs(angle) > MAX_ELEMENT_ANGLE + 1.0e-3f
                        && warned.add("clamp:" + bone.name() + ':' + axis)) {
                    issues.add("'" + bone.rawName() + "' hinges " + round(angle) + " deg about "
                            + AXIS_NAMES[axis] + ", past the +/-" + (int) MAX_ELEMENT_ANGLE
                            + " deg the format carries; the emitted hinge is clamped to "
                            + round(clamped) + " deg so the model loads.");
                }
            } else {
                // No element rotation can carry this: one box, two or three axes.
                // The posed envelope keeps the part readable where its exact tilt
                // would be refused by the format.
                if (warned.add("axis:" + bone.name())) {
                    issues.add("'" + bone.rawName() + "' rotates about more than one axis in the posed"
                            + " item model; its box is emitted as the envelope of that rotation.");
                }
                envelope(element, from, to, posed);
            }
        }
        if (cube.inflate() > 0.0f) {
            element.addProperty("inflate", round(cube.inflate()));
        }
        JsonObject faces = new JsonObject();
        for (String face : FACE_NAMES) {
            ModelCube.Uv uv = cube.face(face);
            if (uv == null) {
                continue;
            }
            JsonObject window = new JsonObject();
            JsonArray corners = new JsonArray();
            // Blockbench stores face UVs in texture pixels; vanilla reads them
            // in the 0..16 window, so they are scaled exactly like the block
            // writer does (ModelJsonWriter). A model authored in project UV
            // space (all windows inside 0..16 on a larger texture) needs no
            // scaling — see {@link #uvScale}.
            corners.add(new JsonPrimitive(round(uv.u1() * uvScale[0])));
            corners.add(new JsonPrimitive(round(uv.v1() * uvScale[1])));
            corners.add(new JsonPrimitive(round(uv.u2() * uvScale[0])));
            corners.add(new JsonPrimitive(round(uv.v2() * uvScale[1])));
            window.add("uv", corners);
            if (uv.rotation() != 0) {
                int normalized = ((uv.rotation() % 360) + 360) % 360;
                if (normalized == 90 || normalized == 180 || normalized == 270) {
                    window.addProperty("rotation", normalized);
                }
            }
            window.addProperty("texture", "#" + Math.max(0, cube.textureIndex()));
            faces.add(face, window);
        }
        if (faces.size() == 0) {
            JsonObject fallback = new JsonObject();
            fallback.add("uv", new JsonArray());
            fallback.addProperty("texture", "#" + Math.max(0, cube.textureIndex()));
            faces.add("north", fallback);
        }
        element.add("faces", faces);
        return element;
    }

    /** The whole-model pose, folded onto the authored display block. */
    private static JsonObject display(Style style, List<ModelBone> bones, int fold,
                                      Map<Integer, BoneDelta> deltas, String sourceName,
                                      List<String> issues, Set<String> warned) {
        JsonObject authored = style.display();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : authored.entrySet()) {
            // Only the eight vanilla contexts may be emitted; an unknown key
            // (e.g. a Blockbench-only "on_shelf") makes the client's model
            // deserializer reject the WHOLE model — the missing-model cube.
            if (!isKnownDisplayContext(entry.getKey())) {
                if (warned.add("display:" + entry.getKey())) {
                    issues.add(sourceName + ": display context '" + entry.getKey()
                            + "' is not a vanilla context and was dropped so the"
                            + " model loads (a vanilla key it may reuse was read"
                            + " only from supported contexts).");
                }
                continue;
            }
            out.add(entry.getKey(), entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject().deepCopy()
                    : entry.getValue());
        }
        if (fold < 0) {
            return out;
        }
        BoneDelta delta = deltas.get(fold);
        if (delta == null) {
            return out;
        }
        float[] animated = blockbench(delta.rotation[0], delta.rotation[1], delta.rotation[2]);
        float[] pivot = pivot(bones.get(fold));
        float[] rotatedPivot = apply(animated, pivot);
        float[] rotatedCentre = apply(animated, new float[]{CENTRE, CENTRE, CENTRE});
        // The clip's transform is p -> A·p + (position + pivot - A·pivot). Folded
        // onto the display it becomes a rotation R·A plus the translation
        // scale·R·(a - C), with a = position + pivot + A·C - A·pivot: the one
        // model-space point whose move the display has to reproduce. A frame that
        // neither rotates nor shifts therefore writes the authored display itself.
        float[] anchor = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            anchor[axis] = delta.position[axis] + pivot[axis] + rotatedCentre[axis] - rotatedPivot[axis];
        }
        for (String context : HELD_CONTEXTS) {
            JsonObject transform = object(out, context);
            if (transform == null) {
                continue;
            }
            float[] base = triple(transform, "rotation", new float[3]);
            float[] translation = triple(transform, "translation", new float[3]);
            float[] scale = triple(transform, "scale", new float[]{1.0f, 1.0f, 1.0f});
            float[] baseMatrix = eulerXYZ(base[0], base[1], base[2]);
            float[] unified = multiply(baseMatrix, animated);
            float pixel = (scale[0] + scale[1] + scale[2]) / 3.0f;
            float[] moved = apply(baseMatrix, new float[]{
                    anchor[0] - CENTRE, anchor[1] - CENTRE, anchor[2] - CENTRE});
            addPair(transform, "rotation", fromMatrix(unified));
            addPair(transform, "translation", new float[]{
                    translation[0] + pixel * moved[0],
                    translation[1] + pixel * moved[1],
                    translation[2] + pixel * moved[2]
            });
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    private static void envelope(JsonObject element, float[] from, float[] to, Transform posed) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            float[] point = {
                    (corner & 1) == 0 ? from[0] : to[0],
                    (corner & 2) == 0 ? from[1] : to[1],
                    (corner & 4) == 0 ? from[2] : to[2]
            };
            float[] posedPoint = add(apply(posed.matrix, point), posed.shift);
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], posedPoint[axis]);
                max[axis] = Math.max(max[axis], posedPoint[axis]);
            }
        }
        addPair(element, "from", min);
        addPair(element, "to", max);
    }

    /** The point a single-axis rotation turns about, or the origin when it is free. */
    private static float[] pivotFor(float[] matrix, float[] shift, int axis) {
        float[] pivot = new float[3];
        int first = (axis + 1) % 3;
        int second = (axis + 2) % 3;
        float m11 = 1.0f - matrix[index(first, first)];
        float m12 = -matrix[index(first, second)];
        float m21 = -matrix[index(second, first)];
        float m22 = 1.0f - matrix[index(second, second)];
        float determinant = m11 * m22 - m12 * m21;
        if (Math.abs(determinant) > 1.0e-6f) {
            pivot[first] = (m22 * shift[first] - m12 * shift[second]) / determinant;
            pivot[second] = (m11 * shift[second] - m21 * shift[first]) / determinant;
        }
        return pivot;
    }

    /** The coordinate axis a rotation matrix turns about, or {@code -1} for none. */
    private static int rotationAxis(float[] matrix) {
        for (int axis = 0; axis < 3; axis++) {
            boolean fixed = Math.abs(matrix[index(axis, axis)] - 1.0f) < 1.0e-3f;
            if (!fixed) {
                continue;
            }
            boolean column = true;
            boolean row = true;
            for (int other = 0; other < 3; other++) {
                if (other == axis) {
                    continue;
                }
                column &= Math.abs(matrix[index(other, axis)]) < 1.0e-3f;
                row &= Math.abs(matrix[index(axis, other)]) < 1.0e-3f;
            }
            if (column && row) {
                return axis;
            }
        }
        return -1;
    }

    private static float rotationAngle(float[] matrix, int axis) {
        int first = (axis + 1) % 3;
        int second = (axis + 2) % 3;
        // A rotation about `axis` in right-handed order reads its sine off the
        // off-diagonal pair of the plane it turns within.
        return (float) Math.toDegrees(Math.atan2(matrix[index(second, first)],
                matrix[index(first, first)]));
    }

    private static boolean isIdentity(float[] matrix) {
        float[] identity = identity();
        for (int i = 0; i < 9; i++) {
            if (Math.abs(matrix[i] - identity[i]) > 1.0e-3f) {
                return false;
            }
        }
        return true;
    }

    /** Blockbench applies a group's Euler triple as Rz·Ry·Rx. */
    private static float[] blockbench(float x, float y, float z) {
        return multiply(multiply(rotation(2, z), rotation(1, y)), rotation(0, x));
    }

    /** A display triple is read by the client as Rx·Ry·Rz. */
    private static float[] eulerXYZ(float x, float y, float z) {
        return multiply(multiply(rotation(0, x), rotation(1, y)), rotation(2, z));
    }

    private static float[] rotation(int axis, float degrees) {
        float[] matrix = identity();
        if (Math.abs(degrees) < 1.0e-6f) {
            return matrix;
        }
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        int first = (axis + 1) % 3;
        int second = (axis + 2) % 3;
        matrix[index(first, first)] = cos;
        matrix[index(first, second)] = -sin;
        matrix[index(second, first)] = sin;
        matrix[index(second, second)] = cos;
        return matrix;
    }

    private static float[] fromMatrix(float[] matrix) {
        // Inverse of eulerXYZ. R = Rx·Ry·Rz has row 0 = [cos y cos z, -cos y sin z,
        // sin y], so the Y angle reads off the top-right cell and X and Z off the
        // two atan2 pairs that follow it.
        float sine = clamp(matrix[index(0, 2)], -1.0f, 1.0f);
        float yAngle = (float) Math.toDegrees(Math.asin(sine));
        float xAngle;
        float zAngle;
        if (Math.abs(Math.abs(sine) - 1.0f) < 1.0e-6f) {
            // Gimbal lock: only X ± Z is recoverable, so all of it goes to X.
            float combined = (float) Math.toDegrees(
                    Math.atan2(matrix[index(1, 0)], matrix[index(1, 1)]));
            xAngle = sine > 0.0f ? combined : -combined;
            zAngle = 0.0f;
        } else {
            xAngle = (float) Math.toDegrees(Math.atan2(-matrix[index(1, 2)], matrix[index(2, 2)]));
            zAngle = (float) Math.toDegrees(Math.atan2(-matrix[index(0, 1)], matrix[index(0, 0)]));
        }
        return new float[]{xAngle, yAngle, zAngle};
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] out = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[index(row, column)] = left[index(row, 0)] * right[index(0, column)]
                        + left[index(row, 1)] * right[index(1, column)]
                        + left[index(row, 2)] * right[index(2, column)];
            }
        }
        return out;
    }

    private static float[] apply(float[] matrix, float[] point) {
        return new float[]{
                matrix[0] * point[0] + matrix[1] * point[1] + matrix[2] * point[2],
                matrix[3] * point[0] + matrix[4] * point[1] + matrix[5] * point[2],
                matrix[6] * point[0] + matrix[7] * point[1] + matrix[8] * point[2]
        };
    }

    private static float[] add(float[] left, float[] right) {
        return new float[]{left[0] + right[0], left[1] + right[1], left[2] + right[2]};
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    private static int index(int row, int column) {
        return row * 3 + column;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Model plumbing
    // ------------------------------------------------------------------

    private static final class BoneDelta {
        private final float[] rotation;
        private final float[] position;

        private BoneDelta(float[] rotation, float[] position) {
            this.rotation = rotation;
            this.position = position;
        }
    }

    private static ModelAnimation animation(ModelData model, String clip) {
        if (model == null || clip == null) {
            return null;
        }
        return model.animations().get(clip.trim().toLowerCase(Locale.ROOT));
    }

    /** The bone's own pivot plus its ancestors', as the parser left them. */
    private static float[] pivot(ModelBone bone) {
        return new float[]{bone.pivotX(), bone.pivotY(), bone.pivotZ()};
    }

    /** The bone and every ancestor above it, outermost first. */
    private static List<Integer> chain(List<ModelBone> bones, int bone) {
        List<Integer> chain = new ArrayList<>();
        int current = bone;
        while (current >= 0 && current < bones.size()) {
            chain.add(0, current);
            current = bones.get(current).parentIndex();
        }
        return chain;
    }

    private static int depth(List<ModelBone> bones, int bone) {
        int depth = 0;
        int current = bones.get(bone).parentIndex();
        while (current >= 0 && current < bones.size() && depth < bones.size()) {
            depth++;
            current = bones.get(current).parentIndex();
        }
        return depth;
    }

    private static boolean isAncestor(List<ModelBone> bones, int possible, int bone) {
        int current = bones.get(bone).parentIndex();
        int guard = 0;
        while (current >= 0 && current < bones.size() && guard++ <= bones.size()) {
            if (current == possible) {
                return true;
            }
            current = bones.get(current).parentIndex();
        }
        return false;
    }

    private static String name(ModelBone bone, int cubeIndex) {
        String raw = bone.rawName().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (raw.isBlank() || "_".equals(raw)) {
            raw = bone.name();
        }
        return raw.isEmpty() || Character.isDigit(raw.charAt(0)) ? "bone_" + raw + "_" + cubeIndex : raw + "_" + cubeIndex;
    }

    /**
     * The model's texture table as vanilla references. Every parsed texture is
     * emitted under its own {@code #index} so a multi-texture model (body,
     * grip, scope...) binds each cube to the sprite it was authored with; the
     * pack path of each reference is derived from the module's texture
     * reference (same namespace/folder, per-texture last segment).
     */
    private static JsonObject textures(Style style, ModelData model) {
        JsonObject textures = new JsonObject();
        String prefix = prefixOf(style.textureReference());
        List<ModelTexture> parsed = model.textures();
        for (int index = 0; index < parsed.size(); index++) {
            ModelTexture texture = parsed.get(index);
            String name = texture == null ? null : TextureAsset.normalizePath(texture.name());
            String reference;
            if (style.textureResolver() != null) {
                reference = style.textureResolver().apply(texture == null || texture.name() == null
                        ? "" : texture.name());
                if (reference == null || reference.isBlank()) {
                    reference = prefix.isEmpty() ? String.valueOf(index)
                            : prefix + "/" + (name == null || name.isBlank()
                                    ? "texture_" + index : name);
                }
            } else if (name == null || name.isBlank()) {
                reference = prefix.isEmpty() ? "texture_" + index : prefix + "/texture_" + index;
            } else {
                reference = prefix.isEmpty() ? name : prefix + "/" + name;
            }
            textures.addProperty(String.valueOf(index), reference.toLowerCase(Locale.ROOT));
        }
        if (parsed.isEmpty() || !textures.has("0")) {
            textures.addProperty("0", style.textureReference());
        }
        textures.addProperty("particle", textures.get("0").getAsString());
        return textures;
    }

    /** Everything before the last {@code /} of a pack reference ({@code ns:item/x} -> {@code ns:item}). */
    private static String prefixOf(String reference) {
        int cut = reference.lastIndexOf('/');
        return cut <= 0 ? "" : reference.substring(0, cut);
    }

    /**
     * The {@code 0..16} window scale for face UVs. Blockbench stores face UVs
     * in texture pixels, so a 64x64 texture needs {@code 16/64} scaling — but
     * a project authored in Blockbench's 0..16 UV space (every window already
     * inside the vanilla window on a larger texture) must not be scaled again.
     */
    private static float[] uvScale(ModelData model) {
        float width = Math.max(1, model.textureWidth());
        float height = Math.max(1, model.textureHeight());
        float[] scale = {16.0f / width, 16.0f / height};
        if (width == 16 && height == 16) {
            return scale;
        }
        boolean projectSpace = true;
        for (ModelBone bone : model.bones()) {
            for (ModelCube cube : bone.cubes()) {
                for (String face : FACE_NAMES) {
                    ModelCube.Uv uv = cube.face(face);
                    if (uv != null && (uv.u1() > 16.001f || uv.u2() > 16.001f
                            || uv.v1() > 16.001f || uv.v2() > 16.001f)) {
                        projectSpace = false;
                        break;
                    }
                }
                if (!projectSpace) {
                    return scale;
                }
            }
        }
        return projectSpace ? new float[]{1.0f, 1.0f} : scale;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static float[] triple(JsonObject parent, String key, float[] fallback) {
        JsonArray array = parent.get(key) != null && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : null;
        if (array == null || array.size() < 3) {
            return fallback.clone();
        }
        float[] out = new float[3];
        for (int index = 0; index < 3; index++) {
            JsonElement value = array.get(index);
            out[index] = value.isJsonNull() ? fallback[index] : value.getAsFloat();
        }
        return out;
    }

    private static void addPair(JsonObject parent, String key, float[] triple) {
        JsonArray array = new JsonArray();
        for (float value : triple) {
            array.add(new JsonPrimitive(round(value)));
        }
        parent.add(key, array);
    }

    private static float round(float value) {
        if (!Float.isFinite(value)) {
            return 0.0f;
        }
        return Math.round(value * 10000.0f) / 10000.0f;
    }
}
