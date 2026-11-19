package com.mineplus.test;

import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java checks for the shared attribute resolver (no Bukkit server, but
 * the stub API jars are on the classpath so the {@code Attribute} enum loads).
 * The resolver must tolerate both spellings of the 1.21.3 rename and return
 * {@code null} — never throw — for an unknown name.
 */
public final class AttributeSeamTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        resolutionChecks();
        if (failures.isEmpty()) {
            System.out.println("AttributeSeamTest: all checks passed");
            return;
        }
        System.out.println("AttributeSeamTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    private static void resolutionChecks() {
        check("MAX_HEALTH resolves on this runtime", MobAttributes.resolveAttribute("MAX_HEALTH") != null);
        check("FOLLOW_RANGE resolves on this runtime", MobAttributes.resolveAttribute("FOLLOW_RANGE") != null);
        check("SCALE resolves on this runtime", MobAttributes.resolveAttribute("SCALE") != null);
        check("GRAVITY resolves on this runtime", MobAttributes.resolveAttribute("GRAVITY") != null);
        check("lowercase canonical name tolerated", MobAttributes.resolveAttribute("max_health") != null);
        check("GENERIC_ prefixed name tolerated", MobAttributes.resolveAttribute("GENERIC_MAX_HEALTH") != null);
        check("unknown attribute resolves null", MobAttributes.resolveAttribute("DEFINITELY_NOT_REAL") == null);
        check("blank name resolves null", MobAttributes.resolveAttribute("   ") == null);
        check("null name resolves null", MobAttributes.resolveAttribute(null) == null);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
