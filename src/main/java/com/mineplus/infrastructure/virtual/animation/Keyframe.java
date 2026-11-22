package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.mob.custom.model.MolangExpression;

/**
 * One animation keyframe. Values keep their Blockbench channel units:
 * rotation in degrees (delta from rest), position in pixels, scale as a
 * multiplier (1 = rest).
 *
 * <p>{@code expressions} carries one compiled Molang expression per component
 * (x, y, z), or {@code null} when the keyframe is purely numeric. A component
 * whose expression is non-null samples from the expression (evaluated at the
 * queried time) instead of the numeric field; the evaluator still lerps
 * between the evaluated endpoints of neighbouring keys.</p>
 */
public record Keyframe(
        float time,
        float x,
        float y,
        float z,
        KeyframeInterpolation interpolation,
        MolangExpression[] expressions
) {

    public Keyframe {
        if (expressions != null && expressions.length != 3) {
            throw new IllegalArgumentException("expressions must carry exactly x, y, z");
        }
    }

    /** Convenience constructor for purely numeric keyframes. */
    public Keyframe(float time, float x, float y, float z, KeyframeInterpolation interpolation) {
        this(time, x, y, z, interpolation, null);
    }

    /** {@code true} when component {@code index} (0=x, 1=y, 2=z) samples an expression. */
    public boolean isAnimated(int index) {
        return expressions != null && expressions[index] != null;
    }
}
