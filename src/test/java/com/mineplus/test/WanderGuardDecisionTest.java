package com.mineplus.test;

import com.mineplus.infrastructure.mob.action.GuardLogic;
import com.mineplus.infrastructure.mob.action.WanderLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Plain-Java checks for the special-mob decision cores (no Bukkit server):
 * wander destination selection (radius / leg / standable rejection rules),
 * the dwell timing contract, and guard threat selection (nearest qualifying
 * within radius; none when nothing qualifies).
 */
public final class WanderGuardDecisionTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        destinationSelection();
        dwellTiming();
        threatSelection();
        if (failures.isEmpty()) {
            System.out.println("WanderGuardDecisionTest: all checks passed");
            return;
        }
        System.out.println("WanderGuardDecisionTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void destinationSelection() {
        // Home at (100, 64, 100); mob standing on it. Every candidate standable.
        WanderLogic.GroundYFinder standable = (x, z, nearY) -> 64.0;
        RandomGenerator random = new java.util.Random(12345L);

        double[] picked = WanderLogic.pickDestination(
                100, 64, 100, 100, 64, 100, 8.0, 32.0, random, standable);
        check("destination found", picked != null);
        check("destination within radius",
                picked != null && Math.hypot(picked[0] - 100, picked[2] - 100) <= 8.0 + 1.0e-6);
        check("destination uses the ground finder's y", picked != null && picked[1] == 64.0);

        // Nothing standable anywhere -> null after the candidate budget.
        WanderLogic.GroundYFinder never = (x, z, nearY) -> Double.NaN;
        double[] stuck = WanderLogic.pickDestination(
                100, 64, 100, 100, 64, 100, 8.0, 32.0, new java.util.Random(7L), never);
        check("no standable candidate yields null", stuck == null);

        // A mob displaced from home still picks inside the home radius, but
        // never a leg longer than maxLeg from its own position.
        WanderLogic.GroundYFinder standableFar = (x, z, nearY) -> 64.0;
        double[] farPick = WanderLogic.pickDestination(
                100, 64, 100, 105, 64, 105, 8.0, 20.0, new java.util.Random(99L), standableFar);
        check("destination respects the home radius",
                farPick != null && Math.hypot(farPick[0] - 100, farPick[2] - 100) <= 8.0 + 1.0e-6);
        check("destination respects the leg clamp",
                farPick != null && Math.hypot(farPick[0] - 105, farPick[2] - 105) <= 20.0 + 1.0e-6);
    }

    private static void dwellTiming() {
        WanderLogic.Dwell dwell = new WanderLogic.Dwell();
        check("a fresh dwell is ready", dwell.ready(0L));
        dwell.beginRest(1_000L, 2_000L, 5_000L, new java.util.Random(3L));
        check("resting is not ready immediately", !dwell.ready(1_000L));
        check("resting is not ready before the window ends", !dwell.ready(2_900L));
        check("resting is ready after the window ends", dwell.ready(7_100L));
        long span = 6_000L;
        for (int i = 0; i < 50; i++) {
            WanderLogic.Dwell sampled = new WanderLogic.Dwell();
            sampled.beginRest(0L, 2_000L, span, new java.util.Random(i));
            if (sampled.ready(1_900L)) {
                check("dwell never ends before its minimum", false);
                return;
            }
            if (!sampled.ready(0L + 2_000L + span + 1L)) {
                check("dwell always ends within its maximum", false);
                return;
            }
        }
        check("dwell window honours min/max across seeds", true);
    }

    private static void threatSelection() {
        // Anchor at (0,0); three candidates at distances 4, 9, 12; radius 10;
        // candidate 2 does not qualify.
        double[] xs = {4.0, 9.0, 12.0};
        double[] zs = {0.0, 0.0, 0.0};
        boolean[] qualifies = {true, false, true};
        int nearest = GuardLogic.pickThreatIndex(0.0, 0.0, 10.0, xs, zs, qualifies, xs.length);
        check("nearest qualifying threat inside the radius wins", nearest == 0);

        boolean[] noneQualify = {false, false, false};
        check("no qualifying threat yields none",
                GuardLogic.pickThreatIndex(0.0, 0.0, 10.0, xs, zs, noneQualify, xs.length) < 0);
        check("outside the radius yields none",
                GuardLogic.pickThreatIndex(0.0, 0.0, 3.0, xs, zs, qualifies, xs.length) < 0);
        check("empty candidate list yields none",
                GuardLogic.pickThreatIndex(0.0, 0.0, 10.0, xs, zs, qualifies, 0) < 0);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
