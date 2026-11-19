package com.mineplus.test;

import com.mineplus.util.SchedulerCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java checks for the shared scheduler probe (no Bukkit server): the
 * Folia detection must work — and be cached — without touching Bukkit classes,
 * and the handle contract must be usable standalone.
 */
public final class SchedulerCompatTest {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        probeChecks();
        if (failures.isEmpty()) {
            System.out.println("SchedulerCompatTest: all checks passed");
            return;
        }
        System.out.println("SchedulerCompatTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    private static void probeChecks() {
        check("folia absent on a plain jvm", !SchedulerCompat.isFolia());
        check("folia probe is cached", SchedulerCompat.isFolia() == SchedulerCompat.isFolia());
        SchedulerCompat.Handle handle = () -> { };
        handle.cancel();
        check("handle is a cancellable contract", true);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
