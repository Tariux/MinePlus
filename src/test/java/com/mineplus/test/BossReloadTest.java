package com.mineplus.test;

import com.mineplus.infrastructure.core.boss.BossFight;
import com.mineplus.infrastructure.core.reload.HotReloader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Plain-Java checks for C6 (boss phases) and C7 (hot reload).
 *
 * Run: java com.mineplus.test.BossReloadTest — exit 0 means all passed.
 */
public final class BossReloadTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        bossPhases();
        bossCooldowns();
        bossValidation();
        hotReload();
        hotReloadFailureIsolation();

        if (failures.isEmpty()) {
            System.out.println("BossReloadTest: all checks passed");
            System.exit(0);
        }
        System.err.println("BossReloadTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void bossPhases() {
        double[] health = {1.0};
        AtomicInteger smash = new AtomicInteger();
        AtomicInteger roar = new AtomicInteger();
        AtomicInteger phaseChanges = new AtomicInteger();

        BossFight fight = new BossFight(() -> health[0], 10)
                .phase("p1", 100, false, "smash")
                .phase("p2", 50, false, "roar")
                .ability("smash", (f, phase) -> smash.incrementAndGet())
                .ability("roar", (f, phase) -> roar.incrementAndGet())
                .onPhaseChange(phaseChanges::incrementAndGet);

        health[0] = 0.9;
        fight.tick();
        check("phase 1 entered and abilities fired", smash.get() == 1 && roar.get() == 0);
        check("phase change notified", phaseChanges.get() == 1);

        health[0] = 0.4;
        fight.tick();
        check("phase 2 entered", fight.currentPhase().name().equals("p2"));
        check("only phase-2 abilities fire on entry", roar.get() == 1 && smash.get() == 1);
        check("one phase step per tick", phaseChanges.get() == 2);
    }

    private static void bossCooldowns() {
        double[] health = {0.9};
        AtomicInteger hits = new AtomicInteger();
        BossFight fight = new BossFight(() -> health[0], 5)
                .phase("p1", 100, true, "hit")
                .ability("hit", (f, p) -> hits.incrementAndGet());

        fight.tick();
        check("entry fires once", hits.get() == 1);
        for (int i = 0; i < 5; i++) {
            fight.tick();
        }
        check("cooldown holds", hits.get() == 1);
        fight.tick();
        check("repeat ability after cooldown", hits.get() == 2);
    }

    private static void bossValidation() {
        boolean rejected = false;
        try {
            new BossFight(() -> 1.0, 10)
                    .phase("a", 100, false)
                    .phase("b", 100, false);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("non-decreasing thresholds rejected", rejected);
    }

    private static void hotReload() {
        Logger log = Logger.getLogger("test");
        HotReloader reloader = new HotReloader(log);
        AtomicInteger applies = new AtomicInteger();
        reloader.register("settings", () -> "v2",
                (prev, fresh) -> applies.incrementAndGet());

        HotReloader.ReloadReport report = reloader.reloadAll();
        check("first reload applies", report.success() && applies.get() == 1);
        check("report names step", report.applied().contains("settings"));

        report = reloader.reloadAll();
        check("second reload applies again", applies.get() == 2 && report.success());
    }

    private static void hotReloadFailureIsolation() {
        Logger log = Logger.getLogger("test");
        HotReloader reloader = new HotReloader(log);
        AtomicInteger good = new AtomicInteger();
        reloader.register("broken", () -> { throw new IllegalStateException("boom"); },
                (p, f) -> good.incrementAndGet());
        reloader.register("healthy", () -> "x", (p, f) -> good.incrementAndGet());

        HotReloader.ReloadReport report = reloader.reloadAll();
        check("failure reported", !report.success() && report.failed().contains("broken"));
        check("healthy step still applied", report.applied().contains("healthy"));
        check("both attempted", good.get() == 1);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
