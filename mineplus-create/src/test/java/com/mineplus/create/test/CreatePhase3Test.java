package com.mineplus.create.test;

import com.mineplus.create.towns.ContractBoard;
import com.mineplus.create.towns.TownRegistry;

import java.util.UUID;

/**
 * Plain-Java test runner for the Phase 3 RP logic: town creation/joining,
 * shared contract progress, tier thresholds and co-op stress bonuses.
 * Exit 0 = all passed.
 */
public final class CreatePhase3Test {

    private static int failures = 0;

    public static void main(String[] args) {
        townLifecycle();
        contractProgress();
        tierThresholds();

        if (failures > 0) {
            System.err.println(failures + " assertion(s) FAILED");
            System.exit(1);
        }
        System.out.println("CreatePhase3Test: all assertions passed");
    }

    static void townLifecycle() {
        TownRegistry towns = new TownRegistry();
        UUID founder = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();

        TownRegistry.Town town = towns.create("Gearhaven", founder);
        check("create returns town", town != null);
        check("founder is member", town.members.contains(founder));
        check("duplicate name denied", towns.create("gearhaven", UUID.randomUUID()) == null);
        check("founder cannot found second town", towns.create("Other", founder) == null);

        check("joiner cannot join unknown town", !towns.join("nowhere", joiner));
        check("joiner joins", towns.join("Gearhaven", joiner));
        check("joiner cannot join twice", !towns.join("Gearhaven", joiner));
        check("townOf resolves", towns.townOf(joiner) == town);
        check("membership count", town.members.size() == 2);

        check("tier starts at 1", town.tier() == 1);
        town.completedContracts = 3;
        check("3 completed -> tier 2", town.tier() == 2);
    }

    static void contractProgress() {
        TownRegistry towns = new TownRegistry();
        ContractBoard board = new ContractBoard();
        board.publish("flour_delivery", "Flour Delivery", 16, 2, 1);
        board.publish("great_press_run", "The Great Press Run", 40, 5, 3);
        check("contract lookup", board.contract("FLOUR_DELIVERY") != null);
        check("duplicate publish ignored", board.all().size() == 2);

        TownRegistry.Town town = towns.create("Milltown", UUID.randomUUID());
        check("tier-1 availability excludes tier-3 contract",
                board.availableFor(town.tier()).stream().noneMatch(c -> c.id.equals("great_press_run")));
        check("tier-1 availability includes flour",
                board.availableFor(town.tier()).stream().anyMatch(c -> c.id.equals("flour_delivery")));

        // Shared progress: several members add units.
        towns.addProgress(town, "flour_delivery", 10);
        towns.addProgress(town, "flour_delivery", 5);
        check("progress sums to 15", towns.progressOf(town, "flour_delivery") == 15);
        check("cannot complete below requirement",
                !towns.completeContract(town, "flour_delivery", 16));
        towns.addProgress(town, "flour_delivery", 1);
        check("completes at requirement", towns.completeContract(town, "flour_delivery", 16));
        check("progress cleared after completion", towns.progressOf(town, "flour_delivery") == 0);
        check("completedContracts counted", town.completedContracts == 1);
    }

    static void tierThresholds() {
        check("tier 1 no bonus", TownRegistry.stressCapacityBonus(1) == 0f);
        check("tier 2 bonus 4", TownRegistry.stressCapacityBonus(2) == 4f);
        check("tier 3 bonus 8", TownRegistry.stressCapacityBonus(3) == 8f);
        check("tier 4 bonus 16", TownRegistry.stressCapacityBonus(4) == 16f);
        check("clamped low", TownRegistry.stressCapacityBonus(0) == 0f);
        check("clamped high", TownRegistry.stressCapacityBonus(99) == 16f);
    }

    static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("ok - " + label);
        } else {
            System.err.println("FAILED - " + label);
            failures++;
        }
    }

    private CreatePhase3Test() {
    }
}
