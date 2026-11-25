package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.core.research.ResearchProgressStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * SQLite-backed research progress (mega-plan C4): the production store
 * behind the {@link ResearchProgressStore} seam. Same {@code owner→nodes}
 * model, one row per owner, saved through the same connection the
 * multiblock persistence uses. Falls back to the file store when no
 * datasource is configured, so single-file setups keep working.
 */
public final class SqliteResearchRepository {

    private final Connection connection;
    private final String table;
    private final Logger logger;

    public SqliteResearchRepository(Connection connection, String table, Logger logger) {
        this.connection = connection;
        this.table = table;
        this.logger = logger;
        ensureTable();
    }

    private void ensureTable() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner TEXT PRIMARY KEY, nodes TEXT NOT NULL)");
        } catch (SQLException e) {
            logger.warning("SqliteResearchRepository: table create failed: " + e.getMessage());
        }
    }

    /** Loads all owners' unlocked node id sets. */
    public Map<String, Set<String>> load() {
        Map<String, Set<String>> progress = new HashMap<>();
        String sql = "SELECT owner, nodes FROM " + table;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Set<String> nodes = new HashSet<>();
                for (String node : result.getString("nodes").split(",")) {
                    if (!node.isBlank()) {
                        nodes.add(node.trim());
                    }
                }
                progress.put(result.getString("owner"), nodes);
            }
        } catch (SQLException e) {
            logger.warning("SqliteResearchRepository: load failed: " + e.getMessage());
        }
        return progress;
    }

    /** Replaces all progress in one transaction. */
    public void save(Map<String, Set<String>> progress) {
        try {
            connection.setAutoCommit(false);
            try (Statement wipe = connection.createStatement()) {
                wipe.executeUpdate("DELETE FROM " + table);
            }
            String sql = "INSERT INTO " + table + "(owner, nodes) VALUES(?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (Map.Entry<String, Set<String>> entry : progress.entrySet()) {
                    insert.setString(1, entry.getKey());
                    insert.setString(2, String.join(",", entry.getValue()));
                    insert.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            logger.warning("SqliteResearchRepository: save failed: " + e.getMessage());
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

    /** File fallback for setups without a datasource. */
    public static ResearchProgressStore fileFallback(Path file, Logger logger) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException | NullPointerException ignored) {
            // no parent dir; the store creates what it can on save
        }
        return new ResearchProgressStore(file, logger);
    }
}
