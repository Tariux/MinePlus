package com.mineplus.test;

import com.mineplus.infrastructure.core.network.KineticGrid;
import com.mineplus.infrastructure.core.network.KineticGrid.Kind;
import com.mineplus.infrastructure.core.network.KineticGrid.KineticNode;
import com.mineplus.infrastructure.core.network.KineticGrid.Rotation;
import java.util.List;

/**
 * Plain-Java checks for the P6 kinetic grid: connectivity, over-stress
 * slow-down, direction plumbing through shafts, and bridge-split rebuilds.
 *
 * Run: java com.mineplus.test.KineticGridTest — exit 0 means all checks passed.
 */
public final class KineticGridTest {

    private static final List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        connectivityAndSplit();
        overStressSlowsNetwork();
        healthyNetworkFullSpeed();
        shaftsCarryConsumersOnlyInResults();
        threeAxisAdjacencyMeshing();
        reputKeepsNetworkIntact();
        drySourceDoesNotDrive();
        networkMembersIncludeShafts();

        if (failures.isEmpty()) {
            System.out.println("KineticGridTest: all checks passed");
            System.exit(0);
        }
        System.err.println("KineticGridTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static long at(int x) {
        return EnergyGridPackHelper.pack(x, 0, 0);
    }

    private static long at3(int x, int y, int z) {
        return EnergyGridPackHelper.pack(x, y, z);
    }

    /**
     * Regression for grid adjacency: machines must mesh into one network on
     * every axis (the six face-neighbours), with cogwheel-to-cogwheel links
     * flagged as meshed (direction flip) and everything else coaxial.
     */
    private static void threeAxisAdjacencyMeshing() {
        KineticGrid grid = new KineticGrid();
        long wheel = at3(0, 0, 0);
        long shaftX = at3(1, 0, 0);
        long shaftY = at3(0, 1, 0);
        long shaftZ = at3(0, 0, 1);
        long cog = at3(0, 0, -1);
        grid.put(wheel, Kind.SOURCE, 64f, 100f, 0f);
        grid.put(shaftX, Kind.SHAFT, 0, 0, 0);
        grid.put(shaftY, Kind.SHAFT, 0, 0, 0);
        grid.put(shaftZ, Kind.SHAFT, 0, 0, 0);
        grid.put(cog, Kind.CONSUMER, 0, 0, 8f);
        // Same adjacency pattern RotationNetworkService.onPlaced() applies.
        grid.link(wheel, shaftX, false);
        grid.link(wheel, shaftY, false);
        grid.link(wheel, shaftZ, false);
        grid.link(wheel, cog, true);

        check("x neighbour linked", grid.connected(wheel, shaftX));
        check("y neighbour linked", grid.connected(wheel, shaftY));
        check("z neighbour linked", grid.connected(wheel, shaftZ));
        check("cogwheel meshed", grid.connected(wheel, cog));

        List<Rotation> rotations = grid.solve();
        check("all linked nodes solve", rotations.size() >= 2);
        check("meshed cog solved with network", rotations.stream().anyMatch(r -> r.key() == cog));
    }

    /** Reuses EnergyGrid.pack (same key space) to keep the two grids interoperable. */
    private static final class EnergyGridPackHelper {
        static long pack(int x, int y, int z) {
            return com.mineplus.infrastructure.core.network.EnergyGrid.pack(x, y, z);
        }
    }

    private static void connectivityAndSplit() {
        KineticGrid grid = new KineticGrid();
        for (int x = 0; x < 4; x++) {
            grid.put(at(x), Kind.SHAFT, 0, 0, 0);
        }
        for (int x = 0; x < 3; x++) {
            grid.link(at(x), at(x + 1), false);
        }
        check("chain connected", grid.connected(at(0), at(3)));
        grid.unlink(at(1), at(2));
        check("bridge split", !grid.connected(at(0), at(3)));
        check("left intact", grid.connected(at(0), at(1)));
        check("right intact", grid.connected(at(2), at(3)));
        grid.remove(at(1));
        check("remove drops node", grid.node(at(1)) == null);
    }

    private static void overStressSlowsNetwork() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 10f, 0f);
        grid.put(at(1), Kind.CONSUMER, 0, 0, 8f);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 8f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);

        List<Rotation> rotations = grid.solve();
        check("all machine nodes solved", rotations.size() == 3);
        check("over-stressed flagged", rotations.stream().allMatch(Rotation::overStressed));
        // capacity 10 of stress 16 -> factor 0.625 -> speed 20
        check("speed scaled by capacity/stress",
                rotations.stream().allMatch(r -> Math.abs(r.speed() - 20f) < 0.01f));
    }

    private static void healthyNetworkFullSpeed() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 100f, 0f);
        grid.put(at(1), Kind.CONSUMER, 0, 0, 40f);
        grid.link(at(0), at(1), false);
        List<Rotation> rotations = grid.solve();
        check("healthy runs at source speed",
                rotations.stream().allMatch(r -> r.speed() == 32f && !r.overStressed()));
    }

    private static void shaftsCarryConsumersOnlyInResults() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 50f, 0f);
        grid.put(at(1), Kind.SHAFT, 0, 0, 0);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);
        List<Rotation> rotations = grid.solve();
        check("shafts excluded from results", rotations.size() == 2);
        check("consumer reached through shaft",
                rotations.stream().anyMatch(r -> r.key() == at(2) && r.speed() == 32f));
    }

    /**
     * Regression: water wheels update their own SU every few seconds, which
     * re-puts the source node. Re-putting must not reset the union-find root —
     * that used to split the wheel off its shaft network, so a connected shaft
     * stopped turning as soon as the first water rescan ran.
     */
    private static void reputKeepsNetworkIntact() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 50f, 0f);
        grid.put(at(1), Kind.SHAFT, 0, 0, 0);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);

        // The dynamic-SU path (RotationNetworkService.setSourceSu).
        grid.put(at(0), Kind.SOURCE, 32f, 40f, 0f);

        check("re-put keeps network connected", grid.connected(at(0), at(2)));
        List<Rotation> rotations = grid.solve();
        check("re-put still reaches consumer",
                rotations.stream().anyMatch(r -> r.key() == at(2) && r.speed() == 32f));
    }

    /** Regression: a dry wheel (capacity 0) must not spin its network. */
    private static void drySourceDoesNotDrive() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 0f, 0f);
        grid.put(at(1), Kind.SHAFT, 0, 0, 0);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);

        List<Rotation> rotations = grid.solve();
        check("dry source drives nothing",
                rotations.stream().allMatch(r -> r.speed() == 0f));
    }

    /** Regression: solve omits shafts, so the service needs the full component. */
    private static void networkMembersIncludeShafts() {
        KineticGrid grid = new KineticGrid();
        grid.put(at(0), Kind.SOURCE, 32f, 50f, 0f);
        grid.put(at(1), Kind.SHAFT, 0, 0, 0);
        grid.put(at(2), Kind.CONSUMER, 0, 0, 10f);
        grid.link(at(0), at(1), false);
        grid.link(at(1), at(2), false);

        List<KineticNode> members = grid.networkMembers(at(0));
        check("members include shaft", members.stream().anyMatch(n -> n.key() == at(1)));
        check("members include consumer", members.stream().anyMatch(n -> n.key() == at(2)));
        check("members size", members.size() == 3);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
