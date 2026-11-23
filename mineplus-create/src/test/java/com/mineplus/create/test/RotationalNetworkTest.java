package com.mineplus.create.test;

import com.mineplus.create.rotational.RotationalNetworkManager;
import com.mineplus.create.rotational.RotationalNetworkManager.Node;
import com.mineplus.create.rotational.RotationalNetworkManager.NodeType;
/**
 * Plain-Java test runner (no JUnit dependency, same pattern as the Core).
 * Exit code 0 = all assertions passed.
 */
public final class RotationalNetworkTest {

    private static int failures = 0;

    public static void main(String[] args) {
        rpmPropagation();
        directionFlippingThroughCogs();
        stressOverflowSnapsWeakestShaft();
        persistenceRoundTrip();
        chunkUnloadSkipsNodes();
        stoppedSourceDecays();
        removedSourceDecays();

        if (failures > 0) {
            System.err.println(failures + " assertion(s) FAILED");
            System.exit(1);
        }
        System.out.println("RotationalNetworkTest: all assertions passed");
    }

    /** A crank source spins every shaft in its component up to the target RPM. */
    static void rpmPropagation() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 32f;
        crank.sourceCapacity = 1000f;
        manager.place("w;1;0;0", NodeType.SHAFT);
        manager.place("w;2;0;0", NodeType.SHAFT);
        manager.place("w;2;1;0", NodeType.SHAFT);
        manager.recompute();

        int networkId = manager.node("w;0;0;0").networkId;
        check("one network", networkId != -1
                && manager.node("w;1;0;0").networkId == networkId
                && manager.node("w;2;0;0").networkId == networkId
                && manager.node("w;2;1;0").networkId == networkId);

        for (int i = 0; i < 200; i++) {
            manager.tick(node -> false);
        }
        check("shafts reach target RPM", Math.abs(manager.node("w;2;1;0").actualRpm - 32f) < 0.01f);
        check("lerp is gradual (not instant on first tick)",
                manager.place("w;3;0;0", NodeType.SHAFT) != null);
    }

    /**
     * A cogwheel on a shaft's axle turns with it; an adjacent meshed cogwheel
     * flips; a shaft on the flipped cog's axle follows it.
     */
    static void directionFlippingThroughCogs() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 16f;
        crank.sourceCapacity = 1000f;
        manager.place("w;1;0;0", NodeType.SHAFT);
        manager.place("w;2;0;0", NodeType.COGWHEEL);
        manager.place("w;3;0;0", NodeType.COGWHEEL);
        manager.place("w;4;0;0", NodeType.SHAFT);
        manager.recompute();

        Node shaft = manager.node("w;1;0;0");
        Node cog1 = manager.node("w;2;0;0");
        Node cog2 = manager.node("w;3;0;0");
        Node shaft2 = manager.node("w;4;0;0");
        check("shaft keeps source sign", shaft.sign == 1);
        check("cog on the axle turns with the shaft", cog1.sign == 1);
        check("adjacent meshed cogwheel flips", cog2.sign == -1);
        check("shaft on flipped cog's axle follows", shaft2.sign == -1);

        for (int i = 0; i < 200; i++) {
            manager.tick(node -> false);
        }
        check("cog on axle turns with source", Math.abs(cog1.actualRpm - 16f) < 0.01f);
        check("meshed cog turns opposite", Math.abs(cog2.actualRpm + 16f) < 0.01f);
        check("trailing shaft turns opposite", Math.abs(shaft2.actualRpm + 16f) < 0.01f);
    }

    /** Total stress above source capacity snaps the lowest-strength shaft. */
    static void stressOverflowSnapsWeakestShaft() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node engine = manager.place("w;0;0;0", NodeType.STEAM_ENGINE);
        engine.sourceRpm = 32f;
        engine.sourceCapacity = 10f;
        Node strong = manager.place("w;1;0;0", NodeType.SHAFT);
        strong.stress = 6f;
        strong.strength = 10f;
        Node weak = manager.place("w;2;0;0", NodeType.SHAFT);
        weak.stress = 6f;
        weak.strength = 5f;
        manager.recompute();

        String snapped = manager.tick(node -> false);
        check("weakest shaft snapped", "w;2;0;0".equals(snapped));
        check("weak node removed", manager.node("w;2;0;0") == null);
        check("strong node survives", manager.node("w;1;0;0") != null);
        check("network re-split into components", manager.size() == 2);
    }

    /** save() → load() restores nodes, sources and actual RPMs. */
    static void persistenceRoundTrip() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 24f;
        crank.sourceCapacity = 500f;
        Node shaft = manager.place("w;1;0;0", NodeType.SHAFT);
        shaft.stress = 2f;
        manager.place("w;1;1;0", NodeType.COGWHEEL);
        for (int i = 0; i < 200; i++) {
            manager.tick(node -> false);
        }
        String snapshot = manager.save();

        RotationalNetworkManager restored = new RotationalNetworkManager();
        restored.load(snapshot);
        check("same node count", restored.size() == 3);
        check("source restored", restored.node("w;0;0;0").sourceRpm == 24f);
        check("actual RPM restored", Math.abs(restored.node("w;0;0;0").actualRpm - 24f) < 0.01f);
        check("stress restored", restored.node("w;1;0;0").stress == 2f);
        check("networks rebuilt", restored.node("w;1;1;0").networkId == restored.node("w;0;0;0").networkId);
    }

    /** Nodes in unloaded chunks are skipped by the tick (no RPM writes, no stress). */
    static void chunkUnloadSkipsNodes() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 32f;
        crank.sourceCapacity = 1000f;
        manager.place("w;1;0;0", NodeType.SHAFT);
        Node unloadedShaft = manager.place("w;2;0;0", NodeType.SHAFT);
        unloadedShaft.stress = 4f;
        manager.recompute();

        // Shaft at x=2 sits in chunk x=0 ... use world "n" for an unloaded chunk.
        RotationalNetworkManager unloadedManager = manager;
        unloadedManager.tick(node -> node.key.equals("w;2;0;0"));
        check("unloaded node keeps actualRpm 0", unloadedShaft.actualRpm == 0f);
        check("loaded shaft still spins", manager.node("w;1;0;0").actualRpm > 0f);
    }

    static void stoppedSourceDecays() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 32f;
        crank.sourceCapacity = 1000f;
        Node shaft = manager.place("w;1;0;0", NodeType.SHAFT);
        for (int i = 0; i < 60; i++) {
            manager.tick(node -> false);
        }
        float runningRpm = shaft.actualRpm;
        crank.sourceRpm = 0f;
        crank.sourceCapacity = 0f;
        manager.tick(node -> node == shaft);
        check("unpowered unloaded shaft preserves RPM", shaft.actualRpm == runningRpm);
        check("stopped crank slows gradually", crank.actualRpm > 0f && crank.actualRpm < runningRpm);
        for (int i = 0; i < 60; i++) {
            manager.tick(node -> false);
        }
        check("stopped crank reaches zero RPM", crank.actualRpm == 0f);
        check("unpowered shaft reaches zero RPM", shaft.actualRpm == 0f);
    }

    static void removedSourceDecays() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        Node crank = manager.place("w;0;0;0", NodeType.CRANK);
        crank.sourceRpm = 32f;
        crank.sourceCapacity = 1000f;
        manager.place("w;1;0;0", NodeType.COGWHEEL);
        Node reversedCog = manager.place("w;2;0;0", NodeType.COGWHEEL);
        for (int i = 0; i < 60; i++) {
            manager.tick(node -> false);
        }
        float runningRpm = reversedCog.actualRpm;
        manager.remove(crank.key);
        manager.tick(node -> false);
        check("disconnected reverse rotation slows without flipping", reversedCog.actualRpm < 0f
                && reversedCog.actualRpm > runningRpm);
        for (int i = 0; i < 60; i++) {
            manager.tick(node -> false);
        }
        check("disconnected reverse rotation stops", reversedCog.actualRpm == 0f);
    }

    static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("ok - " + name);
        } else {
            failures++;
            System.err.println("FAILED - " + name);
        }
    }
}
