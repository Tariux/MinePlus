package com.mineplus.pack.item;

import com.mineplus.infrastructure.virtual.ModelDisplay;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Derives a full vanilla item {@code display} block from the model's own
 * geometry when the author never wrote one (or wrote only some contexts).
 *
 * <p>The model may sit anywhere inside its 16x16x16 authoring space — a rifle
 * blade might occupy a thin 1x2x16 region far from the origin. The solver
 * wraps one axis-aligned box around the covered geometry and aligns that box
 * per view:</p>
 *
 * <ul>
 *   <li><strong>gui / ground / fixed / head</strong> — the box is centered on
 *       the point the vanilla defaults assume (16x16x16 cube center, pixel
 *       (8,8,8)), so an off-origin model sits centered in the inventory slot
 *       and on the ground exactly like a 0..16-authored one would.</li>
 *   <li><strong>the four hand contexts</strong> — the long axis of the box
 *       (principal component of the covered volume) is gripped like a tool
 *       handle, via {@link HeldPoseSolver}; degenerate geometry falls back to
 *       the vanilla handheld pose, still centered.</li>
 * </ul>
 *
 * <p>Authored contexts always win per context: the solver only fills the ones
 * the author never touched. The chain convention matches vanilla — a display
 * transform maps a model vertex {@code p} to {@code t + R·(S·p)} — so the
 * centering delta for a context with rotation {@code R} and scale {@code S}
 * is {@code R·S·(centerTarget − bboxCenter)}.</p>
 */
public final class ItemDisplaySolver {

    /** The pixel the vanilla defaults treat as the model's center. */
    private static final Vector3f VANILLA_CENTER = new Vector3f(8.0f, 8.0f, 8.0f);

    private static final float GUI_SCALE = 0.625f;
    private static final float GROUND_SCALE = 0.25f;
    private static final float HEAD_SCALE = 1.0f;
    private static final float FIXED_SCALE = 1.0f;

    private static final Vector3f GUI_ROTATION = new Vector3f(30.0f, 225.0f, 0.0f);
    private static final Vector3f GROUND_ROTATION = new Vector3f(0.0f, 0.0f, 0.0f);
    private static final Vector3f HEAD_ROTATION = new Vector3f(0.0f, 180.0f, 0.0f);
    private static final Vector3f FIXED_ROTATION = new Vector3f(0.0f, 0.0f, 0.0f);

    private static final Vector3f GROUND_TRANSLATION = new Vector3f(0.0f, 3.0f, 0.0f);

    private static final float FIRSTPERSON_SCALE = 0.68f;
    private static final float THIRDPERSON_SCALE = 0.85f;

    private ItemDisplaySolver() {
    }

    /**
     * Derives the contexts of {@code authored} that are absent, from the
     * model's covered volume (one {@code {minX, minY, minZ, maxX, maxY, maxZ}}
     * box per cube, in model pixels). {@code authored} may be
     * {@link ModelDisplay#EMPTY}; the returned display is never empty — it
     * carries every vanilla context, so the caller can emit it directly.
     */
    public static ModelDisplay derive(float[][] boxes, ModelDisplay authored) {
        Vector3f center = bboxCenter(boxes);
        // Oversized models are shrunk so they fit a vanilla 16 px slot/hand:
        // without this a 33 px rifle overflows the inventory and the screen.
        float fit = fitScale(boxes);

        HeldPoseSolver.Pose pose = HeldPoseSolver.solveBoxes(boxes);
        // When solved, hand translations are already grip-anchored in model
        // space at the pose's own (size-normalized) scale. The vanilla fallback
        // pose assumes a 0..16 model, so its hand translations still need the
        // centering delta (see centered()).
        boolean solved = pose != null;
        if (pose == null) {
            // No dominant axis (e.g. one fat cube): keep the vanilla handheld
            // rotations/translations, but still center the box.
            pose = HeldPoseSolver.vanillaPose();
        }

        Map<String, ModelDisplay.Transform> contexts = new HashMap<>();
        for (Map.Entry<String, ModelDisplay.Transform> entry : authored.contexts().entrySet()) {
            contexts.put(entry.getKey(), entry.getValue());
        }
        contexts.putIfAbsent("gui", centered(GUI_ROTATION, GUI_SCALE * fit, new Vector3f(), center));
        contexts.putIfAbsent("ground", centered(GROUND_ROTATION, GROUND_SCALE * fit, GROUND_TRANSLATION, center));
        contexts.putIfAbsent("fixed", centered(FIXED_ROTATION, FIXED_SCALE * fit, new Vector3f(), center));
        contexts.putIfAbsent("head", centered(HEAD_ROTATION, HEAD_SCALE * fit, new Vector3f(), center));
        float firstScale = solved ? pose.firstpersonScale() : FIRSTPERSON_SCALE * fit;
        float thirdScale = solved ? pose.thirdpersonScale() : THIRDPERSON_SCALE * fit;
        contexts.putIfAbsent("firstperson_righthand", hand(
                pose.firstpersonRotationRight(), pose.firstpersonTranslationRight(),
                firstScale, center, solved));
        contexts.putIfAbsent("firstperson_lefthand", hand(
                pose.firstpersonRotationLeft(), pose.firstpersonTranslationLeft(),
                firstScale, center, solved));
        contexts.putIfAbsent("thirdperson_righthand", hand(
                pose.thirdpersonRotationRight(), pose.thirdpersonTranslationRight(),
                thirdScale, center, solved));
        contexts.putIfAbsent("thirdperson_lefthand", hand(
                pose.thirdpersonRotationLeft(), pose.thirdpersonTranslationLeft(),
                thirdScale, center, solved));
        return new ModelDisplay(contexts);
    }

    /**
     * The fraction that keeps the wrapped box inside a vanilla 16 px cube
     * (1.0 when the model already fits).
     */
    private static float fitScale(float[][] boxes) {
        float span = 0.0f;
        for (int i = 0; i < 3; i++) {
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float[] box : boxes) {
                min = Math.min(min, box[i]);
                max = Math.max(max, box[3 + i]);
            }
            span = Math.max(span, max - min);
        }
        return span <= 16.0f ? 1.0f : 16.0f / span;
    }

    /**
     * The vanilla base transform for a non-held view, re-centered so the
     * wrapped box lands where the vanilla defaults place a 0..16 model.
     */
    private static ModelDisplay.Transform centered(
            Vector3f rotation, float scale, Vector3f translation, Vector3f center) {
        float[] matrix = HeldPoseSolver.eulerXYZ(rotation.x, rotation.y, rotation.z);
        float[] delta = HeldPoseSolver.apply(matrix,
                HeldPoseSolver.scale(new float[]{
                        VANILLA_CENTER.x - center.x,
                        VANILLA_CENTER.y - center.y,
                        VANILLA_CENTER.z - center.z}, scale));
        return new ModelDisplay.Transform(
                new Vector3f(rotation),
                new Vector3f(translation.x + delta[0], translation.y + delta[1], translation.z + delta[2]),
                new Vector3f(scale, scale, scale));
    }

    /**
     * A hand pose. When the solver produced it, the translation is already
     * grip-anchored and no re-centering is needed; the vanilla fallback pose
     * assumes a 0..16 model, so its translation gets the centering delta
     * {@code R·S·(center − bboxCenter)} for the pose's own scale.
     */
    private static ModelDisplay.Transform hand(
            float[] rotation, float[] translation, float scale, Vector3f center, boolean solved) {
        Vector3f out = new Vector3f(translation[0], translation[1], translation[2]);
        if (!solved) {
            float[] matrix = HeldPoseSolver.eulerXYZ(rotation[0], rotation[1], rotation[2]);
            float[] delta = HeldPoseSolver.apply(matrix, HeldPoseSolver.scale(new float[]{
                    VANILLA_CENTER.x - center.x,
                    VANILLA_CENTER.y - center.y,
                    VANILLA_CENTER.z - center.z}, scale));
            out = new Vector3f(out.x + delta[0], out.y + delta[1], out.z + delta[2]);
        }
        return new ModelDisplay.Transform(
                new Vector3f(rotation[0], rotation[1], rotation[2]),
                out,
                new Vector3f(scale, scale, scale));
    }

    /** Center of the box wrapping all cubes; the origin when there is nothing. */
    public static Vector3f bboxCenter(float[][] boxes) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (float[] box : boxes) {
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            minZ = Math.min(minZ, box[2]);
            maxX = Math.max(maxX, box[3]);
            maxY = Math.max(maxY, box[4]);
            maxZ = Math.max(maxZ, box[5]);
        }
        if (minX > maxX) {
            return new Vector3f(VANILLA_CENTER);
        }
        return new Vector3f((minX + maxX) / 2.0f, (minY + maxY) / 2.0f, (minZ + maxZ) / 2.0f);
    }
}
