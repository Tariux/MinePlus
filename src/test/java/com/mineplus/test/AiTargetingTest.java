package com.mineplus.test;

import com.mineplus.infrastructure.mob.GoalArbiter;
import com.mineplus.infrastructure.mob.MobMemory;
import com.mineplus.infrastructure.mob.action.FleeLogic;
import com.mineplus.infrastructure.mob.action.TargetSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plain-Java checks for the AI targeting and movement cores: the situational
 * target scoring (proximity vs. visibility vs. grudge vs. stickiness), the
 * surrounded-flee heading choice, and the hurt-memory threat model
 * (accumulation, linear decay, most-hated selection).
 *
 * Run: java com.mineplus.test.AiTargetingTest — exit 0 means all checks passed.
 */
public final class AiTargetingTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        targetScoring();
        grudgeBeatsDistance();
        visibilityAndStickiness();
        fleeHeading();
        fleeBetweenThreats();
        threatMemory();
        lostSightTrail();
        goalArbitration();

        if (failures.isEmpty()) {
            System.out.println("AiTargetingTest: all checks passed");
            System.exit(0);
        }
        System.out.println("AiTargetingTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }

    private static void targetScoring() {
        UUID id = UUID.randomUUID();
        double radiusSq = 16.0 * 16.0;

        // Edge of radius scores ~0, right on top scores 1.
        check(TargetSelector.score(new TargetSelector.Candidate(id, 256.0, false, false), radiusSq, false) <= 0.0,
                "candidate at the search edge must not outscore a close one on proximity alone");
        check(TargetSelector.score(new TargetSelector.Candidate(id, 0.0, false, false), radiusSq, false) > 0.99,
                "a candidate on top of the mob must score ~1");

        // Best-of over an empty set is null.
        check(TargetSelector.best(new ArrayList<>(), radiusSq, null) == null,
                "no candidates must yield no target");
    }

    private static void grudgeBeatsDistance() {
        UUID near = UUID.randomUUID();
        UUID striker = UUID.randomUUID();
        double radiusSq = 16.0 * 16.0;

        // The mob retaliates: its striker wins even from much farther away.
        List<TargetSelector.Candidate> grudge = List.of(
                new TargetSelector.Candidate(near, 4.0, true, false),
                new TargetSelector.Candidate(striker, 25.0, true, true));
        check(TargetSelector.best(grudge, radiusSq, null).id().equals(striker),
                "the mob must retaliate against its striker over a nearer stranger");

        // Grudges only bend the choice inside the search radius — proximity
        // still decides between two strangers.
        UUID stranger = UUID.randomUUID();
        List<TargetSelector.Candidate> plain = List.of(
                new TargetSelector.Candidate(near, 4.0, true, false),
                new TargetSelector.Candidate(stranger, 25.0, true, false));
        check(TargetSelector.best(plain, radiusSq, null).id().equals(near),
                "without a grudge the nearer candidate must win");
    }

    private static void visibilityAndStickiness() {
        UUID hidden = UUID.randomUUID();
        UUID visible = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        double radiusSq = 16.0 * 16.0;

        List<TargetSelector.Candidate> bySight = List.of(
                new TargetSelector.Candidate(hidden, 4.0, false, false),
                new TargetSelector.Candidate(visible, 16.0, true, false));
        check(TargetSelector.best(bySight, radiusSq, null).id().equals(visible),
                "a visible candidate must beat an unseen closer one");

        // Stickiness: with everything else equal, the current target wins.
        UUID other = UUID.randomUUID();
        List<TargetSelector.Candidate> tie = List.of(
                new TargetSelector.Candidate(other, 25.0, true, false),
                new TargetSelector.Candidate(current, 25.0, true, false));
        check(TargetSelector.best(tie, radiusSq, current).id().equals(current),
                "the committed target must not flicker to an equal stranger");
    }

    private static void fleeHeading() {
        // Threat due east: the away heading is west (pi radians).
        double west = Math.PI;
        double chosen = FleeLogic.bestHeading(west, new double[0], 0.5, 16);
        double err = Math.abs(Math.cos(chosen - west));
        check(err > 0.99, "with no secondaries the mob must flee straight away from the threat");

        // Zero samples clamps up instead of dividing by zero.
        check(!Double.isNaN(FleeLogic.bestHeading(0.0, null, 0.0, 0)),
                "degenerate sample count must still return a finite heading");
    }

    private static void fleeBetweenThreats() {
        // Primary threat east (flee west); a second threat west (flee east).
        // Straight-away west runs into threat two; the best heading is a gap
        // at a right angle-ish compromise — anywhere except due west or due east.
        double primaryWest = Math.PI;
        double secondaryEast = 0.0;
        double chosen = FleeLogic.bestHeading(primaryWest, new double[]{secondaryEast}, 0.5, 16);
        double aligned = Math.abs(Math.cos(chosen - primaryWest));
        check(aligned < 0.999,
                "a mob between two threats must not run straight into the second one");
        double intoSecond = Math.abs(Math.cos(chosen - secondaryEast));
        check(intoSecond < 0.999,
                "a mob between two threats must not run straight into the primary either");
    }

    private static void goalArbitration() {
        List<GoalArbiter.Goal> drives = List.of(
                new GoalArbiter.Goal("wander", 0.3),
                new GoalArbiter.Goal("attack_nearest", 0.5),
                new GoalArbiter.Goal("flee", 0.8));

        // Most urgent first, regardless of registration order.
        List<GoalArbiter.Ordered> plain = GoalArbiter.order(drives, null);
        check(plain.get(0).id().equals("flee") && plain.get(2).id().equals("wander"),
                "goals must be tried most-urgent-first");

        // Stickiness: a mid-way goal beats an equal challenger.
        List<GoalArbiter.Goal> tie = List.of(
                new GoalArbiter.Goal("wander", 0.5),
                new GoalArbiter.Goal("attack_nearest", 0.5));
        check(GoalArbiter.order(tie, "attack_nearest").get(0).id().equals("attack_nearest"),
                "the pursued goal must beat an equal challenger");

        // ...but urgency still wins over stickiness.
        List<GoalArbiter.Goal> panic = List.of(
                new GoalArbiter.Goal("wander", 0.5),
                new GoalArbiter.Goal("flee", 0.9));
        check(GoalArbiter.order(panic, "wander").get(0).id().equals("flee"),
                "a more urgent goal must pre-empt the pursued one");

        // Equal urgencies without a current goal keep registration order.
        List<GoalArbiter.Goal> equal = List.of(
                new GoalArbiter.Goal("a", 0.5),
                new GoalArbiter.Goal("b", 0.5));
        check(GoalArbiter.order(equal, null).get(0).id().equals("a"),
                "equal urgencies must keep registration order");

        check(GoalArbiter.order(new ArrayList<>(), null).isEmpty(),
                "no goals must order to nothing");
    }

    private static void threatMemory() {
        MobMemory memory = new MobMemory();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        long t0 = 1000L;

        memory.recordThreat(a, 10.0, t0);
        check(memory.threatValue(a, t0) > 9.9, "fresh threat must be carried at full weight");
        check(memory.mostHated(t0).equals(a), "the striker must be the most hated");

        // Bigger, later strike takes over.
        memory.recordThreat(b, 20.0, t0 + 10);
        check(memory.mostHated(t0 + 10).equals(b), "a harder strike must become the most hated");

        // Linear decay: after HURT_MEMORY_TICKS nothing is left, and the
        // decayed entry stops being reported.
        long late = t0 + 10 + MobMemory.HURT_MEMORY_TICKS + 5;
        check(memory.threatValue(b, late) == 0.0, "threat must fully decay after the memory window");
        check(memory.mostHated(late) == null, "no remembered threat must mean no most-hated");

        // Silence decays; stacking re-adds on top of what is left.
        memory.recordThreat(a, 10.0, t0);
        double half = memory.threatValue(a, t0 + MobMemory.HURT_MEMORY_TICKS / 2);
        check(half > 4.0 && half < 6.0, "threat must decay linearly across the memory window");
        memory.recordThreat(a, 5.0, t0 + MobMemory.HURT_MEMORY_TICKS / 2);
        double stacked = memory.threatValue(a, t0 + MobMemory.HURT_MEMORY_TICKS / 2);
        check(stacked > half, "a fresh strike must add on top of the decayed remainder");
    }

    private static void lostSightTrail() {
        // Fresh trail, hunter not there yet: keep pursuing the last-seen spot.
        check(com.mineplus.infrastructure.mob.action.LostSight.decide(20L, 100.0, 2.25, 100L)
                        == com.mineplus.infrastructure.mob.action.LostSight.Verdict.PURSUE,
                "a warm trail away from the hunter must be pursued");

        // Reached the spot, nothing there: the trail is dead.
        check(com.mineplus.infrastructure.mob.action.LostSight.decide(20L, 1.0, 2.25, 100L)
                        == com.mineplus.infrastructure.mob.action.LostSight.Verdict.ARRIVED,
                "arriving at the last-seen spot must end the search");

        // Trail went cold before arrival.
        check(com.mineplus.infrastructure.mob.action.LostSight.decide(101L, 100.0, 2.25, 100L)
                        == com.mineplus.infrastructure.mob.action.LostSight.Verdict.EXPIRED,
                "a trail older than the grace window must expire");

        // Expiry wins over arrival: a hunter standing on the old spot does not
        // keep "arriving" forever once the trail is cold.
        check(com.mineplus.infrastructure.mob.action.LostSight.decide(200L, 0.0, 2.25, 100L)
                        == com.mineplus.infrastructure.mob.action.LostSight.Verdict.EXPIRED,
                "an expired trail must not count as a fresh arrival");
    }
}
