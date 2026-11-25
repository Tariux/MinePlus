package com.mineplus.test;

import com.mineplus.infrastructure.core.network.KineticGrid;
import com.mineplus.infrastructure.core.network.KineticGrid.Kind;
import com.mineplus.infrastructure.core.network.KineticGrid.Rotation;
import com.mineplus.infrastructure.core.network.LogisticsGrid;
import com.mineplus.infrastructure.core.network.LogisticsGrid.Delivery;
import com.mineplus.infrastructure.core.network.LogisticsGrid.Mode;
import java.util.List;

/**
 * Plain-Java checks for the P6 continuation: kinetic direction flipping
 * across belt crossings, and logistics insert/extract routing.
 *
 * Run: java com.mineplus.test.NetworkRoutingTest — exit 0 means all passed.
 */
public final class NetworkRoutingTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        kineticFlipPropagation();
        kineticFlipClearedOnUnlink();
        logisticsInsertNearestStorage();
        logisticsFilterAndExtraction();

        if (failures.isEmpty()) {
            System.out.println("NetworkRoutingTest: all checks passed");
            System.exit(0);
        }
        System.err.println("NetworkRoutingTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static long at(int x) {
        return com.mineplus.infrastructure.core.network.EnergyGrid.pack(x, 0, 0);
    }

    private static void kineticFlipPropagation() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 100f, 0f);
        grid.put(at(1), Kind.SHAFT, 0, 0, 0);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 10f);
        grid.put(at(3), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);
        grid.link(at(2), at(3), true); // belt crossing: flip beyond here

        List<Rotation> rotations = grid.solve();
        check("flip propagates sign",
                rotations.stream().anyMatch(r -> r.key() == at(3) && r.direction() == -1)
                        && rotations.stream().anyMatch(r -> r.key() == at(2) && r.direction() == 1));
    }

    private static void kineticFlipClearedOnUnlink() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 100f, 0f);
        grid.put(at(1), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), true);
        grid.unlink(at(0), at(1));
        grid.link(at(0), at(1), false);
        List<Rotation> rotations = grid.solve();
        check("re-linked without flip is positive",
                rotations.stream().filter(r -> r.key() == at(1))
                        .allMatch(r -> r.direction() == 1));
    }

    private static void logisticsInsertNearestStorage() {
        LogisticsGrid grid = new LogisticsGrid();
        // origin pipe at 0; near storage at 1 (full), far storage at 3.
        grid.put(at(0), Mode.PIPE, 0, 0, null);
        grid.put(at(1), Mode.STORAGE, 2, 2, null);
        grid.put(at(3), Mode.STORAGE, 5, 0, null);
        grid.link(at(0), at(1));
        grid.link(at(1), at(3));

        Delivery d = grid.insert(at(0), "minecraft:iron_ingot", 3);
        check("routed past full storage", d != null && d.to() == at(3));
        check("amount delivered", d != null && d.amount() == 3);
        check("storage used updated", grid.node(at(3)).used() == 3);

        Delivery none = grid.insert(at(0), "minecraft:iron_ingot", 3);
        check("partial when full", none != null && none.amount() == 2);

        Delivery nowhere = grid.insert(at(3), "minecraft:diamond", 1);
        // at(3) is storage with 5 cap, 5 used -> no room; no other storage.
        check("rejects when all full", nowhere == null);
    }

    private static void logisticsFilterAndExtraction() {
        LogisticsGrid grid = new LogisticsGrid();
        grid.put(at(0), Mode.REQUESTER, 0, 0, "minecraft:iron_ingot");
        grid.put(at(1), Mode.PIPE, 0, 0, null);
        grid.put(at(2), Mode.STORAGE, 10, 4, "minecraft:iron_ingot");
        grid.put(at(4), Mode.STORAGE, 10, 4, "minecraft:gold_ingot");
        grid.link(at(0), at(1));
        grid.link(at(1), at(2));
        grid.link(at(1), at(4));

        Delivery d = grid.extract(at(0), "minecraft:iron_ingot", 2);
        check("extract takes from matching storage",
                d != null && d.from() == at(2) && d.amount() == 2);
        check("extract decremented", grid.node(at(2)).used() == 2);
        check("filtered storage untouched", grid.node(at(4)).used() == 4);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
