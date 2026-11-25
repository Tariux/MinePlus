package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.core.minigame.GameMap;
import com.mineplus.infrastructure.core.minigame.GameStateMachine;
import com.mineplus.infrastructure.core.minigame.InstanceManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed minigame instance persistence (mega-plan D3): snapshots
 * every live instance so games survive a server restart. One row per
 * instance (id, type, machine snapshot as JSON-ish text columns); loads
 * back through {@link InstanceManager#restore}. Saved through the same
 * connection the multiblock and research persistence use.
 */
public final class SqliteMinigameInstanceRepository {

    private final Connection connection;
    private final String table;
    private final Logger logger;

    public SqliteMinigameInstanceRepository(Connection connection, String table, Logger logger) {
        this.connection = connection;
        this.table = table;
        this.logger = logger;
        ensureTable();
    }

    private void ensureTable() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id TEXT PRIMARY KEY, type_id TEXT NOT NULL, state TEXT NOT NULL, "
                    + "teams TEXT NOT NULL, scoreboard TEXT, spectators TEXT)");
        } catch (SQLException e) {
            logger.warning("SqliteMinigameInstanceRepository: table create failed: " + e.getMessage());
        }
    }

    /** Replaces all stored instances with the manager's live ones, in one transaction. */
    public void save(InstanceManager manager) {
        try {
            connection.setAutoCommit(false);
            try (Statement wipe = connection.createStatement()) {
                wipe.executeUpdate("DELETE FROM " + table);
            }
            String sql = "INSERT INTO " + table
                    + "(id, type_id, state, teams, scoreboard, spectators) VALUES(?, ?, ?, ?, ?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (InstanceManager.Instance instance : manager.all()) {
                    GameStateMachine machine = instance.state();
                    insert.setString(1, instance.id().toString());
                    insert.setString(2, instance.typeId());
                    insert.setString(3, machine.state().name());
                    insert.setString(4, encodeTeams(machine));
                    insert.setString(5, encodeScoreboard(instance.map()));
                    insert.setString(6, encodeSpectators(instance.map()));
                    insert.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            logger.warning("SqliteMinigameInstanceRepository: save failed: " + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // connection is already broken; next save retries
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    /** Restores every stored instance into the manager. Returns the restored ids. */
    public List<UUID> load(InstanceManager manager) {
        List<UUID> restored = new ArrayList<>();
        String sql = "SELECT id, type_id, state, teams, scoreboard, spectators FROM " + table;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                try {
                    UUID id = UUID.fromString(result.getString("id"));
                    String typeId = result.getString("type_id");
                    GameStateMachine.Snapshot snapshot = new GameStateMachine.Snapshot(
                            GameStateMachine.State.valueOf(result.getString("state")),
                            decodeTeams(result.getString("teams")));
                    manager.restore(id, typeId, snapshot);
                    GameMap map = manager.instance(id).map();
                    decodeScoreboard(result.getString("scoreboard"), map);
                    decodeSpectators(result.getString("spectators"), map);
                    restored.add(id);
                } catch (RuntimeException e) {
                    // Tolerant load: one corrupt row must not lose the rest.
                    logger.warning("SqliteMinigameInstanceRepository: row skipped: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            logger.warning("SqliteMinigameInstanceRepository: load failed: " + e.getMessage());
        }
        return restored;
    }

    private static String encodeTeams(GameStateMachine machine) {
        StringBuilder out = new StringBuilder();
        for (var entry : machine.snapshot().teams().entrySet()) {
            GameStateMachine.TeamSnapshot team = entry.getValue();
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(entry.getKey()).append('|');
            out.append(team.members().stream().map(UUID::toString)
                    .reduce((a, b) -> a + "," + b).orElse(""));
            out.append('|').append(team.score());
            out.append('|').append(team.eliminated());
        }
        return out.toString();
    }

    private static java.util.Map<String, GameStateMachine.TeamSnapshot> decodeTeams(String raw) {
        java.util.Map<String, GameStateMachine.TeamSnapshot> teams = new java.util.LinkedHashMap<>();
        for (String team : raw.split(";", -1)) {
            if (team.isBlank()) {
                continue;
            }
            String[] fields = team.split("\\|", -1);
            String id = fields[0];
            String memberBlob = fields.length > 1 ? fields[1] : "";
            List<UUID> members = new ArrayList<>();
            if (!memberBlob.isBlank()) {
                for (String member : memberBlob.split(",")) {
                    members.add(UUID.fromString(member.trim()));
                }
            }
            int score = fields.length > 2 && !fields[2].isBlank() ? Integer.parseInt(fields[2]) : 0;
            boolean eliminated = fields.length > 3 && Boolean.parseBoolean(fields[3]);
            teams.put(id, new GameStateMachine.TeamSnapshot(members, score, eliminated));
        }
        return teams;
    }

    private static String encodeScoreboard(GameMap map) {
        StringBuilder out = new StringBuilder();
        for (GameMap.Line line : map.scoreboard()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(line.key()).append('|').append(line.text()).append('|').append(line.priority());
        }
        return out.toString();
    }

    private static void decodeScoreboard(String raw, GameMap map) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<GameMap.Line> lines = new ArrayList<>();
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("\\|", 3);
            if (parts.length == 3) {
                lines.add(new GameMap.Line(parts[0], parts[1], Integer.parseInt(parts[2])));
            }
        }
        if (!lines.isEmpty()) {
            map.setScoreboard(lines);
        }
    }

    private static String encodeSpectators(GameMap map) {
        return map.spectators().stream().map(UUID::toString)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static void decodeSpectators(String raw, GameMap map) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String spectator : raw.split(",")) {
            try {
                map.addSpectator(UUID.fromString(spectator.trim()));
            } catch (IllegalArgumentException ignored) {
                // corrupt uuid: skip it, the rest load
            }
        }
    }
}
