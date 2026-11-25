package com.mineplus.test;

import com.mineplus.infrastructure.core.minigame.GameMap;
import com.mineplus.infrastructure.core.minigame.GameMap.Line;
import com.mineplus.infrastructure.core.minigame.GameMap.Marker;
import com.mineplus.infrastructure.core.minigame.GameMap.Region;
import java.util.List;
import java.util.UUID;

/**
 * Plain-Java checks for the D1 part 2: regions, round-robin marker
 * claiming, scoreboard ordering, and spectator bookkeeping.
 *
 * Run: java com.mineplus.test.GameMapTest — exit 0 means all passed.
 */
public final class GameMapTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        regionsAndContainment();
        markerRoundRobin();
        scoreboardOrdering();
        spectators();

        if (failures.isEmpty()) {
            System.out.println("GameMapTest: all checks passed");
            System.exit(0);
        }
        System.err.println("GameMapTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void regionsAndContainment() {
        GameMap map = new GameMap();
        Region arena = new Region("arena", "world", 0, 0, 0, 10, 5, 10);
        map.addRegion(arena);
        check("region stored", map.region("arena") == arena);
        check("contains inside", arena.contains("world", 5, 2, 5));
        check("rejects other world", !arena.contains("nether", 5, 2, 5));
        check("rejects outside", !arena.contains("world", 11, 2, 5));
        check("volume correct", arena.volume() == 11 * 6 * 11);
    }

    private static void markerRoundRobin() {
        GameMap map = new GameMap();
        map.addMarker(new Marker("spawn", "world", 0, 1, 0));
        map.addMarker(new Marker("spawn", "world", 5, 1, 5));
        map.addMarker(new Marker("spawn", "world", 9, 1, 9));

        check("first claim is first marker", map.claimMarker("spawn").x() == 0);
        check("second claim advances", map.claimMarker("spawn").x() == 5);
        check("third claim advances", map.claimMarker("spawn").x() == 9);
        check("wraps around", map.claimMarker("spawn").x() == 0);
        check("unknown marker is null", map.claimMarker("goal") == null);

        map.resetMarkers();
        check("reset restarts cursor", map.claimMarker("spawn").x() == 0);
    }

    private static void scoreboardOrdering() {
        GameMap map = new GameMap();
        map.setScoreboard(List.of(
                new Line("title", "Sky Wars", 0),
                new Line("score-red", "Red: 3", 2),
                new Line("score-blue", "Blue: 1", 1)));
        List<Line> lines = map.scoreboard();
        check("priority order", lines.get(0).key().equals("title")
                && lines.get(1).key().equals("score-blue")
                && lines.get(2).key().equals("score-red"));
        boolean immutable = false;
        try {
            lines.add(new Line("x", "x", 9));
        } catch (UnsupportedOperationException e) {
            immutable = true;
        }
        check("scoreboard list immutable", immutable);
    }

    private static void spectators() {
        GameMap map = new GameMap();
        UUID p = UUID.randomUUID();
        check("add spectator", map.addSpectator(p));
        check("idempotent add returns false", !map.addSpectator(p));
        check("isSpectator true", map.isSpectator(p));
        map.removeSpectator(p);
        check("removed", !map.isSpectator(p));
        map.removeSpectator(p); // no-op, must not throw
        check("remove absent is no-op", true);
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
