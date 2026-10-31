package com.mineplus.infrastructure.mob.custom.model;

import java.util.Locale;

/**
 * The tiny slice of Molang animation data values actually need: a constant
 * number. Blockbench keys store their values as strings that may be Molang
 * expressions ({@code math.sin(q.anim_time)}); this evaluator accepts plain
 * numeric literals (including signs, decimals and exponents) and rejects
 * everything else, which callers translate into {@code 0} plus a recorded
 * issue — a clip authored with expressions still plays its numeric keys.
 */
public final class Molang {

    private Molang() {
    }

    /**
     * Parses {@code expr} as a numeric constant.
     *
     * @return the value, or {@code null} when it is not a plain number
     */
    public static Double numericConstant(String expr) {
        if (expr == null) {
            return null;
        }
        String trimmed = expr.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // A quoted Molang string ('25') is still just a constant when the
        // content is numeric.
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '\'' && trimmed.charAt(trimmed.length() - 1) == '\'') {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("q.")
                || trimmed.toLowerCase(Locale.ROOT).startsWith("math.")) {
            return null;
        }
        try {
            double value = Double.parseDouble(trimmed);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }
}
