package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.PersistenceConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteMigrationRunner {

    private final PersistenceConfig config;

    public SqliteMigrationRunner(PersistenceConfig config) {
        this.config = config;
    }

    public void migrate(Connection connection) throws SQLException {
        String multiBlocks = config.tablePrefix() + "multiblocks";
        String virtualBlocks = config.tablePrefix() + "virtualblocks";
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
                        id TEXT PRIMARY KEY,
                        payload TEXT NOT NULL
                    )
                    """.formatted(virtualBlocks));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.formatted(meta));
        }
    }
}
