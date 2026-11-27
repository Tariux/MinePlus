package com.mineplus.test;

import com.mineplus.infrastructure.core.block.BlockBreakTracker;
import com.mineplus.infrastructure.core.block.BlockBreakingRules;
import com.mineplus.infrastructure.core.block.BlockDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;

/** Plain-Java checks for hardness progress and tool gating. */
public final class BlockBreakingTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        progressAccumulates();
        timeoutResetsProgress();
        toolGating();
        speedTable();

        if (failures.isEmpty()) {
            System.out.println("BlockBreakingTest: all checks passed");
            return;
        }
        for (String failure : failures) {
            System.err.println("BlockBreakingTest: " + failure);
        }
        System.exit(1);
    }

    private static void progressAccumulates() {
        BlockBreakTracker tracker = new BlockBreakTracker();
        UUID player = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        long now = 1_000L;
        BlockBreakTracker.State first = tracker.damage(player, block, 1.0, 1.0, now);
        expect("first hit does not complete", !first.complete(), "completed on first hit");
        expect("first hit has no stage", first.stage() == 0, "stage=" + first.stage());
        BlockBreakTracker.State second = tracker.damage(player, block, 1.0, 1.0, now + 250L);
        expect("progress grows", second.fraction() > first.fraction(),
                first.fraction() + " -> " + second.fraction());
        BlockBreakTracker.State last = second;
        for (long tick = 250L; tick <= 3_000L && !last.complete(); tick += 250L) {
            last = tracker.damage(player, block, 1.0, 1.0, now + tick);
        }
        expect("progress completes while mining", last.complete(), "fraction=" + last.fraction());
        expect("final stage is nine", last.stage() == 9, "stage=" + last.stage());
    }

    private static void timeoutResetsProgress() {
        BlockBreakTracker tracker = new BlockBreakTracker();
        UUID player = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        tracker.damage(player, block, 1.0, 1.0, 1_000L);
        tracker.damage(player, block, 1.0, 1.0, 1_900L);
        double afterPause = tracker.progress(player, block);
        expect("long pause drops progress", afterPause <= 0.1, "progress=" + afterPause);
    }

    private static void toolGating() {
        BlockDefinition.Tool axe = new BlockDefinition.Tool("axe", "wooden");
        expect("wrong tool class refused",
                BlockBreakingRules.refusal(axe, Material.IRON_PICKAXE) != null, "allowed");
        expect("right tool class allowed",
                BlockBreakingRules.refusal(axe, Material.IRON_AXE) == null, "refused");
        expect("tier below requirement refused",
                BlockBreakingRules.refusal(new BlockDefinition.Tool("axe", "diamond"),
                        Material.WOODEN_AXE) != null, "allowed");
        expect("tier met allowed",
                BlockBreakingRules.refusal(new BlockDefinition.Tool("axe", "diamond"),
                        Material.DIAMOND_AXE) == null, "refused");
        expect("any tool allowed",
                BlockBreakingRules.refusal(BlockDefinition.Tool.NONE, Material.DIRT) == null, "refused");
        expect("axe detected", BlockBreakingRules.typeOf(Material.GOLDEN_AXE).equals("axe"),
                BlockBreakingRules.typeOf(Material.GOLDEN_AXE));
        expect("tier detected", BlockBreakingRules.tierOf(Material.NETHERITE_PICKAXE).equals("netherite"),
                BlockBreakingRules.tierOf(Material.NETHERITE_PICKAXE));
        expect("hand has no tier", BlockBreakingRules.tierOf(Material.AIR).equals("any"),
                BlockBreakingRules.tierOf(Material.AIR));
    }

    private static void speedTable() {
        expect("wooden is faster than hand",
                BlockBreakingRules.speed("wooden") > BlockBreakingRules.speed("any"), "not faster");
        expect("netherite above diamond",
                BlockBreakingRules.speed("netherite") > BlockBreakingRules.speed("diamond"), "not faster");
    }

    private static void expect(String name, boolean condition, String detail) {
        if (!condition) {
            failures.add(name + " (" + detail + ")");
        }
    }
}
