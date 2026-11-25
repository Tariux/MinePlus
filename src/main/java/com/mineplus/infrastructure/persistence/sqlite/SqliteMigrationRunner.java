package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.PersistenceConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteMigrationRunner {

    /**
     * Bump when the persisted schema changes in a way older code cannot read;
     * the runner refuses to open a database written by a newer schema instead
     * of misreading it.
     */
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_VERSION_KEY = "schema_version";

    private final PersistenceConfig config;

    public SqliteMigrationRunner(PersistenceConfig config) {
        this.config = config;
    }

    public void migrate(Connection connection) throws SQLException {
        String multiBlocks = config.tablePrefix() + "multiblocks";
        String meta = config.tablePrefix() + "meta";
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY,
                        payload TEXT NOT NULL
                    )
                    """.formatted(multiBlocks));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.formatted(meta));
            enforceSchemaVersion(statement);
        }
    }

    private void enforceSchemaVersion(Statement statement) throws SQLException {
        int recorded = 0;
        try (var result = statement.executeQuery(
                "SELECT value FROM " + config.tablePrefix() + "meta WHERE key = '" + SCHEMA_VERSION_KEY + "'")) {
            if (result.next()) {
                recorded = Integer.parseInt(result.getString("value").trim());
            }
        }
        if (recorded > SCHEMA_VERSION) {
            throw new SQLException("Database schema version " + recorded
                    + " is newer than this build supports (version " + SCHEMA_VERSION
                    + "). Upgrade the plugin or restore a backup; refusing to read incompatible data.");
        }
        if (recorded < SCHEMA_VERSION) {
            statement.execute("INSERT OR REPLACE INTO " + config.tablePrefix() + "meta(key, value) VALUES('"
                    + SCHEMA_VERSION_KEY + "', '" + SCHEMA_VERSION + "')");
        }
    }
}
