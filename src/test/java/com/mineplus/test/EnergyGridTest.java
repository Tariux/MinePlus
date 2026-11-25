package com.mineplus.test;

import com.mineplus.infrastructure.core.network.EnergyGrid;
import com.mineplus.infrastructure.core.network.EnergyGrid.Balance;
import com.mineplus.infrastructure.core.network.EnergyGrid.Node;
import com.mineplus.infrastructure.core.network.EnergyGrid.Role;
import java.util.List;

/**
 * Plain-Java checks for the P6 energy grid: union-find connectivity across
 * bridges, component rebuild after unlink/remove, fair-shortfall balancing
 * and the coordinate packing round trip.
 *
 * Run: java com.mineplus.test.EnergyGridTest — exit 0 means all checks passed.
 */
public final class EnergyGridTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        packingRoundTrip();
        connectivityAndBridgeSplit();
        removeRebuildsSplit();
        fairShortfallBalancing();
        dirtyTracking();

        if (failures.isEmpty()) {
            System.out.println("EnergyGridTest: all checks passed");
            System.exit(0);
        }
        System.err.println("EnergyGridTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void packingRoundTrip() {
        long key = EnergyGrid.pack(-30_000_000, 255, 30_000_000);
        check("pack round trip extreme",
                EnergyGrid.unpackX(key) == -30_000_000
                        && EnergyGrid.unpackY(key) == 255
                        && EnergyGrid.unpackZ(key) == 30_000_000);
        long k2 = EnergyGrid.pack(0, 64, -1);
        check("pack round trip small",
                EnergyGrid.unpackX(k2) == 0
                        && EnergyGrid.unpackY(k2) == 64
                        && EnergyGrid.unpackZ(k2) == -1);
        check("pack collides only with itself",
                EnergyGrid.pack(1, 2, 3) != EnergyGrid.pack(3, 2, 1));
    }

    private static void connectivityAndBridgeSplit() {
        EnergyGrid grid = new EnergyGrid();
        long a = EnergyGrid.pack(0, 0, 0);
        long b = EnergyGrid.pack(1, 0, 0);
        long c = EnergyGrid.pack(2, 0, 0);
        long d = EnergyGrid.pack(3, 0, 0);
        grid.put(a, Role.CONDUIT, 0, 0);
        grid.put(b, Role.CONDUIT, 0, 0);
        grid.put(c, Role.CONDUIT, 0, 0);
        grid.put(d, Role.CONDUIT, 0, 0);
        grid.link(a, b);
        grid.link(b, c);
        grid.link(c, d);
        check("chain connected", grid.connected(a, d));

        grid.unlink(b, c);
        check("bridge unlink splits", !grid.connected(a, d));
        check("left half intact", grid.connected(a, b));
        check("right half intact", grid.connected(c, d));

        grid.link(b, c);
        check("relink rejoins", grid.connected(a, d));
    }

    private static void removeRebuildsSplit() {
        EnergyGrid grid = new EnergyGrid();
        long a = EnergyGrid.pack(0, 5, 0);
        long b = EnergyGrid.pack(1, 5, 0);
        long c = EnergyGrid.pack(2, 5, 0);
        grid.put(a, Role.CONDUIT, 0, 0);
        grid.put(b, Role.CONDUIT, 0, 0);
        grid.put(c, Role.CONDUIT, 0, 0);
        grid.link(a, b);
        grid.link(b, c);

        grid.remove(b);
        check("removed node gone", !grid.contains(b));
        check("neighbours no longer connected", !grid.connected(a, c));
    }

    private static void fairShortfallBalancing() {
        EnergyGrid grid = new EnergyGrid();
        long src = EnergyGrid.pack(0, 0, 0);
        long c1 = EnergyGrid.pack(1, 0, 0);
        long c2 = EnergyGrid.pack(2, 0, 0);
        long iso = EnergyGrid.pack(9, 0, 9);
        grid.put(src, Role.SOURCE, 60, 0);
        grid.put(c1, Role.CONSUMER, 0, 50);
        grid.put(c2, Role.CONSUMER, 0, 50);
        grid.put(iso, Role.CONSUMER, 0, 10);
        grid.link(src, c1);
        grid.link(c1, c2);

        List<Balance> balances = grid.balance();
        check("only linked consumers balanced",
                balances.size() == 3
                        && balances.stream().filter(x -> x.demand() == 50)
                                .allMatch(x -> x.received() == 30));
        check("fair shortfall factor 0.6",
                balances.stream().filter(x -> x.demand() == 50)
                        .allMatch(x -> x.received() == 30));
        check("isolated consumer starves, own component",
                balances.stream().filter(x -> x.demand() == 10)
                        .allMatch(x -> x.received() == 0));

        // Upsize the source: everything saturated at 1.0 next pass.
        grid.put(src, Role.SOURCE, 100, 0);
        balances = grid.balance();
        check("full supply meets demand, only dirty component recomputed",
                balances.size() == 2
                        && balances.stream().filter(x -> x.demand() == 50)
                                .allMatch(x -> x.received() == 50));
    }

    private static void dirtyTracking() {
        EnergyGrid grid = new EnergyGrid();
        long a = EnergyGrid.pack(0, 0, 0);
        long b = EnergyGrid.pack(1, 0, 0);
        grid.put(a, Role.SOURCE, 10, 0);
        grid.put(b, Role.CONSUMER, 0, 5);
        check("dirty after put", grid.isDirty(b));
        grid.link(a, b);
        grid.balance();
        check("clean after balance", !grid.isDirty(a) && !grid.isDirty(b));
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
