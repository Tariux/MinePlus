package com.mineplus.create.test;

import com.mineplus.create.electric.ElectricFeature;
import com.mineplus.create.journal.ResearchLadder;
import com.mineplus.create.machines.MachineSpecs;
import com.mineplus.create.ores.OrePlan;
import com.mineplus.create.rotational.RotationalNetworkManager;
import com.mineplus.create.rotational.RotationalNetworkManager.NodeType;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Plain-Java test runner for Phase 2/4 logic: research gating, steam burn
 * math, dynamo power math, ore wilderness rule. Exit 0 = all passed.
 */
public final class CreatePhase2Test {

    private static int failures = 0;

    public static void main(String[] args) {
        researchGating();
        steamBurnMath();
        dynamoPowerMath();
        oreWildernessRule();
        steamEngineJoinsNetwork();

        if (failures > 0) {
            System.err.println(failures + " assertion(s) FAILED");
            System.exit(1);
        }
        System.out.println("CreatePhase2Test: all assertions passed");
    }

    /** T1 requires T0, XP is spent only on success, tiers map correctly. */
    static void researchGating() {
        ResearchLadder ladder = new ResearchLadder();

        // T0 is free and gateless.
        Set<String> unlocked = new HashSet<>();
        ResearchLadder.Research t0 = ladder.unlock(ResearchLadder.TIER_0, unlocked, 0);
        check("T0 unlocks free", t0 != null);
        unlocked.add(t0.id);

        check("T1 without XP denied", ladder.unlock(ResearchLadder.TIER_1, unlocked, 2) == null);
        check("T1 with XP and prerequisite ok",
                ladder.unlock(ResearchLadder.TIER_1, unlocked, 3) != null);

        Set<String> t1Only = new HashSet<>(Set.of(ResearchLadder.TIER_0, ResearchLadder.TIER_1));
        check("T2 without T1 denied", ladder.unlock(ResearchLadder.TIER_2, Set.of(ResearchLadder.TIER_0), 99) == null);
        check("T2 with T1 and XP ok", ladder.unlock(ResearchLadder.TIER_2, t1Only, 8) != null);

        check("tier mapping", ResearchLadder.tierOf(List.of(
                ResearchLadder.TIER_0, ResearchLadder.TIER_1, ResearchLadder.TIER_2)) == 2);
        check("tier of nothing is -1", ResearchLadder.tierOf(List.of()) == -1);

        Set<String> locked = new HashSet<>();
        ResearchLadder.Research again = ladder.unlock(ResearchLadder.TIER_0, unlocked, 99);
        check("already-unlocked denied", again == null && locked.isEmpty());
    }

    /** Coal burn accumulates and never goes negative. */
    static void steamBurnMath() {
        check("0 coal keeps burn", MachineSpecs.burnTicksAfter(100, 0) == 100);
        check("1 coal adds 1600", MachineSpecs.burnTicksAfter(0, 1) == 1600);
        check("negative items clamp", MachineSpecs.burnTicksAfter(10, -3) == 10);
        check("negative current clamps", MachineSpecs.burnTicksAfter(-50, 0) == 0);
    }

    /** Dynamo converts |RPM| to FE and respects the buffer cap. */
    static void dynamoPowerMath() {
        com.mineplus.create.electric.DynamoBufferBank bank =
                new com.mineplus.create.electric.DynamoBufferBank();
        bank.register("w;0;0;0");

        check("empty buffer draws 0", bank.draw("w;0;0;0", 10) == 0);

        bank.generateTick(key -> 32f); // 32 RPM -> 16 FE/tick
        check("dynamo generates from RPM", bank.buffer("w;0;0;0") == 16);
        check("draw takes what is stored", bank.draw("w;0;0;0", 999) == 16
                && bank.buffer("w;0;0;0") == 0);

        bank.generateTick(key -> -40f); // negative RPM still generates
        check("negative RPM generates", bank.buffer("w;0;0;0") == 20);

        for (int i = 0; i < 100_000; i++) {
            bank.generateTick(key -> 400f); // 200/tick, would overflow the cap
        }
        check("buffer capped at limit", bank.buffer("w;0;0;0") == MachineSpecs.DYNAMO_BUFFER_FE);
    }

    /** Ore veins only in wilderness chunks, inside their Y bands. */
    static void oreWildernessRule() {
        check("no veins near settlements",
                OrePlan.plan(0, 0, new Random(42), false).isEmpty());

        List<OrePlan.Vein> veins = OrePlan.plan(10, -4, new Random(7), true);
        check("wilderness veins planned", veins.size() == 2); // VEINS_PER_CHUNK
        for (OrePlan.Vein vein : veins) {
            boolean inBand = switch (vein.ore) {
                case OrePlan.ZINC -> vein.y >= 5 && vein.y <= 40;
                case OrePlan.BAUXITE -> vein.y >= 24 && vein.y <= 56;
                default -> vein.y >= 48 && vein.y <= 72;
            };
            check("vein " + vein.ore + " in its Y band", inBand);
            // floorDiv handles negative chunk coordinates correctly
            // (x = chunk*16 + [0,15) can be positive while the chunk is -4).
            check("vein inside chunk", Math.floorDiv(vein.x, 16) == 10 && Math.floorDiv(vein.z, 16) == -4);
        }
    }

    /** A fueled steam engine is a real rotational source driving shafts. */
    static void steamEngineJoinsNetwork() {
        RotationalNetworkManager manager = new RotationalNetworkManager();
        RotationalNetworkManager.Node engine = manager.place("w;0;0;0", NodeType.STEAM_ENGINE);
        engine.sourceRpm = 48f;
        engine.sourceCapacity = 64f;
        manager.place("w;1;0;0", NodeType.SHAFT);
        manager.recompute();

        check("engine is a source", MachineSpecs.isSource(MachineSpecs.STEAM_ENGINE));
        check("same network", manager.node("w;1;0;0").networkId == manager.node("w;0;0;0").networkId);
        for (int i = 0; i < 200; i++) {
            manager.tick(node -> false);
        }
        check("shaft spins at engine RPM", Math.abs(manager.node("w;1;0;0").actualRpm - 48f) < 0.01f);
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
