package com.mineplus.test;

import com.mineplus.infrastructure.mob.custom.model.Molang;
import com.mineplus.infrastructure.mob.custom.model.MolangExpression;
import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.animation.AnimationEvaluator;
import com.mineplus.infrastructure.virtual.animation.Keyframe;
import com.mineplus.infrastructure.virtual.animation.KeyframeInterpolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain-Java runner for the Molang expression evaluator and its wiring into
 * keyframe sampling. Run {@code main} after compiling; a non-zero exit code
 * means a check failed.
 */
public final class MolangEvaluatorTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        constants();
        arithmetic();
        functions();
        queries();
        rejects();
        numericConstantBridge();
        keyframeSampling();
        if (failures.isEmpty()) {
            System.out.println("MolangEvaluatorTest: all checks passed");
            return;
        }
        System.out.println("MolangEvaluatorTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    private static void constants() {
        check("plain number", eval("25", 0, 0) == 25f);
        check("negative number", eval("-90", 0, 0) == -90f);
        check("decimal", eval("16.875", 0, 0) == 16.875f);
        check("quoted number", eval("'25'", 0, 0) == 25f);
        check("constant addition folds", eval("90+45", 0, 0) == 135f);
        check("pi", eval("math.pi", 0, 0) > 3.14f && eval("math.pi", 0, 0) < 3.15f);
    }

    private static void arithmetic() {
        check("precedence", eval("2+3*4", 0, 0) == 14f);
        check("parentheses", eval("(2+3)*4", 0, 0) == 20f);
        check("unary minus expression", eval("-(1+2)", 0, 0) == -3f);
        check("modulo", eval("10%3", 0, 0) == 1f);
        check("divide by zero samples zero", eval("1/0", 0, 0) == 0f);
    }

    private static void functions() {
        check("sin", Math.abs(eval("math.sin(0)", 0, 0)) < 1.0e-6f);
        check("cos", eval("math.cos(0)", 0, 0) == 1f);
        check("abs", eval("math.abs(-5)", 0, 0) == 5f);
        check("floor", eval("math.floor(2.7)", 0, 0) == 2f);
        check("ceil", eval("math.ceil(2.1)", 0, 0) == 3f);
        check("round", eval("math.round(2.5)", 0, 0) == 3f);
        check("sqrt clamps negatives", eval("math.sqrt(-4)", 0, 0) == 0f);
        check("pow", eval("math.pow(2, 10)", 0, 0) == 1024f);
        check("min", eval("math.min(3, 7)", 0, 0) == 3f);
        check("max", eval("math.max(3, 7)", 0, 0) == 7f);
        check("mod zero-safe", eval("math.mod(5, 0)", 0, 0) == 0f);
        check("clamp low", eval("math.clamp(-4, 0, 10)", 0, 0) == 0f);
        check("clamp high", eval("math.clamp(40, 0, 10)", 0, 0) == 10f);
        check("lerp midpoint", eval("math.lerp(0, 10, 0.5)", 0, 0) == 5f);
    }

    private static void queries() {
        check("anim_time reads clock", eval("q.anim_time", 1.5f, 9f) == 1.5f);
        check("life_time reads clock", eval("q.life_time", 1.5f, 9f) == 9f);
        check("sin of anim_time", eval("math.sin(q.anim_time * math.pi)", 0.5f, 0f) == 1f);
        check("query alias", eval("query.anim_time", 3f, 0f) == 3f);
        check("unknown query samples zero",
                MolangExpression.compile("q.head_yaw") != null
                        && eval("q.head_yaw", 2f, 2f) == 0f);
        check("unknown query flags variable use",
                MolangExpression.compile("q.head_yaw").usesUnknownVariables());
        check("known query does not flag variable use",
                !MolangExpression.compile("q.anim_time").usesUnknownVariables());
    }

    private static void rejects() {
        check("null rejects", MolangExpression.compile(null) == null);
        check("empty rejects", MolangExpression.compile("") == null);
        check("garbage rejects", MolangExpression.compile("hello world") == null);
        check("unbalanced rejects", MolangExpression.compile("math.sin(1") == null);
        check("trailing junk rejects", MolangExpression.compile("1 2") == null);
    }

    private static void numericConstantBridge() {
        check("numericConstant keeps plain numbers", Molang.numericConstant("25") == 25.0);
        check("numericConstant folds constant expressions", Molang.numericConstant("90+45") == 135.0);
        Double timed = Molang.numericConstant("q.anim_time");
        check("numericConstant rejects time expressions", timed == null);
        Double garbage = Molang.numericConstant("nonsense");
        check("numericConstant rejects garbage", garbage == null);
        check("numericConstant rejects null", Molang.numericConstant(null) == null);
    }

    private static void keyframeSampling() {
        MolangExpression wave = MolangExpression.compile("math.sin(q.anim_time * math.pi)");
        // Constant track: two purely numeric keys — the numeric path is untouched.
        Keyframe constant = new Keyframe(0f, 0f, 0f, 0f, KeyframeInterpolation.LINEAR);
        Keyframe constantEnd = new Keyframe(1f, 0f, 0f, 0f, KeyframeInterpolation.LINEAR);
        List<Keyframe> track = new ArrayList<>(List.of(constant, constantEnd));

        float[] out = new float[3];
        AnimationEvaluator.sampleForTest(track, 0.25f, out);
        check("constant key samples zero", out[0] == 0f && out[1] == 0f && out[2] == 0f);

        // Animated track: y rides sin(pi * t); x/z stay numeric.
        track = new ArrayList<>(List.of(
                new Keyframe(0f, 0f, 0f, 0f, KeyframeInterpolation.LINEAR, new MolangExpression[]{null, wave, null}),
                new Keyframe(1f, 0f, 0f, 0f, KeyframeInterpolation.LINEAR, new MolangExpression[]{null, wave, null})));
        AnimationEvaluator.sampleForTest(track, 0.5f, out);
        check("expression key peaks at half clip", Math.abs(out[1] - 1f) < 1.0e-4f);
        AnimationEvaluator.sampleForTest(track, 0.25f, out);
        check("expression key is continuous", out[1] > 0.6f && out[1] < 0.8f);
        AnimationEvaluator.sampleForTest(track, 0f, out);
        check("expression key starts at zero", out[1] == 0f);

        // Mixed: expression endpoint lerped against a numeric endpoint (z=2).
        track = new ArrayList<>(List.of(
                new Keyframe(0f, 0f, 0f, 0f, KeyframeInterpolation.LINEAR, new MolangExpression[]{null, wave, null}),
                new Keyframe(1f, 0f, 0f, 2f, KeyframeInterpolation.LINEAR)));
        AnimationEvaluator.sampleForTest(track, 0.5f, out);
        check("mixed track lerps numeric side", out[2] == 1f);

        // Clip record still accepts expression keyframes end to end.
        AnimationClip clip = new AnimationClip("wave", null, 1f,
                Map.of("bone", new AnimationClip.BoneAnimation(track, List.of(), List.of())));
        check("clip keeps animated track", clip.animators().get("bone").rotation().size() == 2);
    }

    private static float eval(String expr, float animTime, float lifeTime) {
        MolangExpression compiled = MolangExpression.compile(expr);
        if (compiled == null) {
            failures.add("compile failed: " + expr);
            return Float.NaN;
        }
        return compiled.eval(animTime, lifeTime);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
