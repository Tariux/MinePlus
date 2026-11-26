package com.mineplus.test;

import com.mineplus.infrastructure.render.PackFallbackNotice;

import java.util.List;

/**
 * Plain-Java checks for the pack-to-virtual fallback notice (machine root
 * rule: pack-first always, a texel render must be logged loudly with the
 * model named):
 *
 * <ul>
 *   <li>the first fallback for a model reports and warns,</li>
 *   <li>a repeat fallback for the same model stays silent (one log per model),</li>
 *   <li>blank or null model keys are reported under a placeholder,</li>
 *   <li>{@link PackFallbackNotice#clear} lets a repeat failure warn again.</li>
 * </ul>
 *
 * Run: java com.mineplus.test.PackFallbackNoticeTest — exit 0 means all checks passed.
 */
public final class PackFallbackNoticeTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        PackFallbackNotice.clear();
        firstFallbackWarns();
        repeatFallbackIsSilent();
        blankKeyUsesPlaceholder();
        clearLetsItWarnAgain();

        if (failures.isEmpty()) {
            System.out.println("PackFallbackNoticeTest: all checks passed");
            System.exit(0);
        }
        System.err.println("PackFallbackNoticeTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void firstFallbackWarns() {
        PackFallbackNotice.clear();
        check("first fallback reports", PackFallbackNotice.report("water_wheel", "pack item missing"));
        check("reported map has the model", PackFallbackNotice.reported().containsKey("water_wheel"));
        check("reported map keeps the reason",
                "pack item missing".equals(PackFallbackNotice.reported().get("water_wheel")));
    }

    private static void repeatFallbackIsSilent() {
        check("same model does not warn twice", !PackFallbackNotice.report("water_wheel", "again"));
        check("reported map still has one entry per model", PackFallbackNotice.reported().size() == 1);
    }

    private static void blankKeyUsesPlaceholder() {
        check("null model key reports", PackFallbackNotice.report(null, null));
        check("placeholder key present", PackFallbackNotice.reported().containsKey("<unknown>"));
        check("null reason defaults", "unknown reason".equals(PackFallbackNotice.reported().get("<unknown>")));
        check("blank key shares the placeholder and stays silent", !PackFallbackNotice.report("  ", "x"));
    }

    private static void clearLetsItWarnAgain() {
        PackFallbackNotice.clear();
        check("cleared map is empty", PackFallbackNotice.reported().isEmpty());
        check("after clear the same model warns again",
                PackFallbackNotice.report("water_wheel", "republished"));
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            System.out.println("ok - " + label);
        } else {
            failures.add(label);
            System.err.println("FAIL - " + label);
        }
    }
}
