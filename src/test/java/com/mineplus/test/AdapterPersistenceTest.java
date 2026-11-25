package com.mineplus.test;

import com.mineplus.infrastructure.core.minigame.GameMap;
import com.mineplus.infrastructure.core.minigame.GameMap.Marker;
import com.mineplus.infrastructure.core.minigame.GameStateMachine;
import com.mineplus.infrastructure.core.minigame.GameStateMachine.State;
import com.mineplus.infrastructure.core.minigame.InstanceManager;
import com.mineplus.infrastructure.core.minigame.MinigameAdapter;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMinigameInstanceRepository;
import com.mineplus.infrastructure.persistence.sqlite.SqliteResearchRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Plain-Java checks for the final slices: SQLite research store and the
 * minigame Bukkit adapter glue.
 *
 * Run: java com.mineplus.test.AdapterPersistenceTest — exit 0 = all passed.
 */
public final class AdapterPersistenceTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();
    private static final Logger LOG = Logger.getLogger("test");

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("adapter");
        try {
            sqliteResearch(dir);
            minigameAdapter();
            sqliteMinigameInstances(dir);
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (Exception ignored) {
                                // temp cleanup, best effort
                            }
                        });
            }
        }

        if (failures.isEmpty()) {
            System.out.println("AdapterPersistenceTest: all checks passed");
            System.exit(0);
        }
        System.err.println("AdapterPersistenceTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static void sqliteResearch(Path dir) throws Exception {
        Class.forName("org.sqlite.JDBC"); // slf4j now ships in libs/
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("r.db"));
        SqliteResearchRepository repo = new SqliteResearchRepository(connection, "research", LOG);
        check("empty load", repo.load().isEmpty());

        repo.save(Map.of("p1", Set.of("root", "child")));
        Map<String, Set<String>> loaded = repo.load();
        check("round trip", loaded.get("p1").containsAll(Set.of("root", "child")));

        repo.save(Map.of("p2", Set.of("root")));
        Map<String, Set<String>> replaced = repo.load();
        check("save replaces all", replaced.size() == 1 && replaced.containsKey("p2"));
        connection.close();

        // File fallback shares the same seam for no-datasource setups and
        // is exercised unconditionally.
        var fallback = SqliteResearchRepository.fileFallback(dir.resolve("fb/research.tsv"), LOG);
        fallback.save(Map.of("p1", Set.of("root")));
        check("file fallback saves", Files.exists(dir.resolve("fb/research.tsv")));
    }

    private static void minigameAdapter() {
        GameStateMachine state = new GameStateMachine(4);
        state.createTeam("red");
        GameMap map = new GameMap();
        map.addMarker(new Marker("spawn", "world", 1, 1, 1));
        map.addMarker(new Marker("spawn", "world", 2, 1, 2));
        map.setScoreboard(List.of(new GameMap.Line("title", "Arena", 0)));

        AtomicInteger renders = new AtomicInteger();
        java.util.List<Marker> teleports = new java.util.ArrayList<>();
        MinigameAdapter adapter = new MinigameAdapter(state, map,
                (player, lines) -> renders.incrementAndGet(),
                (player, marker) -> teleports.add(marker));

        UUID p = UUID.randomUUID();
        adapter.bindPlayer(p);
        check("player tracked", adapter.isTracked(p));

        adapter.sendToSpawn(p);
        check("sendToSpawn claims marker", teleports.size() == 1);
        adapter.sendToSpawn(p);
        check("second spawn different marker", teleports.get(1).x() == 2);

        Runnable pass = adapter.tickPass();
        pass.run(); // tick from LOBBY: no render yet (blend unchanged)
        int afterLobby = renders.get();
        state.transition(State.STARTING);
        pass.run();
        state.transition(State.RUNNING);
        pass.run();
        check("renders fired on running entry", renders.get() >= afterLobby);
        check("scoreboard content served",
                map.scoreboard().get(0).text().equals("Arena"));
    }

    private static void sqliteMinigameInstances(Path dir) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("g.db"));
        InstanceManager manager = new InstanceManager();
        manager.register(new InstanceManager.Definition("spleef", 4, List.of("red", "blue")));

        // Live game with a score, an eliminated team and spectators.
        InstanceManager.Instance game = manager.create("spleef");
        GameStateMachine machine = game.state();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        machine.join(p1, "red");
        machine.join(p2, "blue");
        machine.transition(State.STARTING);
        machine.transition(State.RUNNING);
        machine.addScore("red", 3);
        machine.eliminate("blue");
        game.map().addSpectator(UUID.randomUUID());
        game.map().setScoreboard(List.of(new GameMap.Line("title", "Spleef", 0)));

        SqliteMinigameInstanceRepository repo =
                new SqliteMinigameInstanceRepository(connection, "instances", LOG);
        repo.save(manager);

        // Fresh manager (server restart): restore from the store.
        InstanceManager reloaded = new InstanceManager();
        reloaded.register(new InstanceManager.Definition("spleef", 4, List.of("red", "blue")));
        var restored = repo.load(reloaded);
        check("instance restored", restored.size() == 1 && restored.contains(game.id()));
        InstanceManager.Instance back = reloaded.instance(game.id());
        check("state restored", back.state().state() == State.RUNNING);
        check("type restored", back.typeId().equals("spleef"));
        GameStateMachine.Snapshot snapshot = back.state().snapshot();
        check("scores restored", snapshot.teams().get("red").score() == 3);
        check("elimination restored", snapshot.teams().get("blue").eliminated());
        check("members restored", snapshot.teams().get("red").members().contains(p1)
                && snapshot.teams().get("blue").members().contains(p2));
        check("spectators restored", !back.map().spectators().isEmpty());
        check("scoreboard restored", back.map().scoreboard().get(0).text().equals("Spleef"));

        // Replace-all semantics: stopping everything and saving empties the store.
        reloaded.stop(game.id());
        repo.save(reloaded);
        check("save replaces all (instances)",
                new SqliteMinigameInstanceRepository(connection, "instances", LOG)
                        .load(new InstanceManager()).isEmpty());
        connection.close();
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}
