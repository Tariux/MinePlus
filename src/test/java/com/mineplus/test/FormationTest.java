package com.mineplus.test;

import com.mineplus.infrastructure.core.multiblock.formation.FormationManager;
import com.mineplus.infrastructure.core.multiblock.formation.FormationMatcher;
import com.mineplus.infrastructure.core.multiblock.formation.FormationPattern;
import com.mineplus.infrastructure.core.multiblock.formation.FormationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain-Java checks for the formation pattern model and matcher rotation math
 * (no Bukkit world needed: matchAt is exercised via rotatedOffsets, the
 * live-tracking logic via FormationManager with a stub HookBus-free path).
 *
 * Run: java com.mineplus.test.FormationTest — exit 0 means all checks passed.
 */
public final class FormationTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        patternCellsAndBounds();
        rotationMath();
        registryReplace();
        managerBreakTracking();

        if (failures.isEmpty()) {
            System.out.println("FormationTest: all checks passed");
            System.exit(0);
        }
        System.err.println("FormationTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void patternCellsAndBounds() {
        FormationPattern p = FormationPattern.of("test:anvil")
                .cell(0, 0, 0, "IRON_BLOCK")
                .cell(1, 0, 0, "IRON_BLOCK")
                .cell(0, 1, 0, "ANVIL")
                .build();
        check("cells preserved", p.cells().size() == 3);
        check("material upper-cased",
                p.cells().get("0,1,0").equals("ANVIL"));
        int[] b = p.bounds();
        check("bounds x=2 y=2 z=1", b[0] == 2 && b[1] == 2 && b[2] == 1);
        check("not rotation locked", !p.rotationLocked());

        FormationPattern locked = FormationPattern.of("test:locked")
                .cell(0, 0, 0, "STONE")
                .rotationLocked()
                .build();
        check("rotation locked flag", locked.rotationLocked());
    }

    private static void rotationMath() {
        FormationPattern p = FormationPattern.of("test:r")
                .cell(1, 0, 0, "STONE")
                .build();
        // rotation 0: +x stays +x
        Map<String, int[]> r0 = FormationMatcher.rotatedOffsets(p, 0);
        check("rot0 offset", eq(r0.get("1,0,0"), 1, 0, 0));
        // rotation 1: +x -> +z (90° CCW about Y)
        Map<String, int[]> r1 = FormationMatcher.rotatedOffsets(p, 1);
        check("rot1 +x -> +z", eq(r1.get("1,0,0"), 0, 0, 1));
        // rotation 2: +x -> -x
        Map<String, int[]> r2 = FormationMatcher.rotatedOffsets(p, 2);
        check("rot2 +x -> -x", eq(r2.get("1,0,0"), -1, 0, 0));
        // rotation 3: +x -> -z (270° CCW about Y)
        Map<String, int[]> r3 = FormationMatcher.rotatedOffsets(p, 3);
        check("rot3 +x -> -z", eq(r3.get("1,0,0"), 0, 0, -1));
    }

    private static void registryReplace() {
        FormationRegistry registry = new FormationRegistry();
        registry.register(FormationPattern.of("a").cell(0, 0, 0, "STONE").build());
        registry.register(FormationPattern.of("a").cell(0, 0, 0, "DIRT").build());
        check("duplicate id replaces", registry.get("a").cells().get("0,0,0").equals("DIRT"));
        check("patterns view size", registry.patternsView().size() == 1);
        registry.unregister("a");
        check("unregister removes", registry.get("a") == null);
    }

    private static void managerBreakTracking() {
        // FormationManager's live-tracking path uses Location/World, which need
        // a server; here we verify the class wires registry + tracking map
        // through its public surface without a world (construct with a no-op
        // HookBus stands in for the real event bus).
        FormationRegistry registry = new FormationRegistry();
        com.mineplus.infrastructure.core.events.HookBus bus =
                new com.mineplus.infrastructure.core.events.HookBus(java.util.logging.Logger.getLogger("test"));
        FormationManager manager = new FormationManager(registry, bus);
        check("manager exposes registry", manager.registry() == registry);
        check("no live formations initially", manager.liveFormationsView().isEmpty());
    }

    private static boolean eq(int[] a, int x, int y, int z) {
        return a != null && a.length == 3 && a[0] == x && a[1] == y && a[2] == z;
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
