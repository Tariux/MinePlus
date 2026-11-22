package com.mineplus.infrastructure.mob.custom.model;

/**
 * The slice of Molang animation data values actually need.
 *
 * <p>{@link #numericConstant} folds any variable-free expression (Blockbench
 * writes keys as strings: {@code "25"}, {@code "90+45"}, {@code "math.pi"}),
 * while expressions that reference time ({@code q.anim_time}) compile via
 * {@link MolangExpression#compile} and sample per frame in the pose evaluator.</p>
 */
public final class Molang {

    private Molang() {
    }

    /**
     * Parses {@code expr} as a numeric constant.
     *
     * @return the value, or {@code null} when it is not numeric or references
     *         a query/variable (those need per-frame evaluation instead)
     */
    public static Double numericConstant(String expr) {
        if (expr == null) {
            return null;
        }
        MolangExpression compiled = MolangExpression.compile(expr);
        if (compiled == null || compiled.hasVariables() || compiled.usesUnknownVariables()) {
            return null;
        }
        float value = compiled.eval(0.0f, 0.0f);
        return Float.isFinite(value) ? (double) value : null;
    }
}
