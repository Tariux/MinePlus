package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.PersistenceConfig;
import com.mineplus.util.DebugLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public final class SqliteConnectionFactory {

    private final PersistenceConfig config;
    private final Logger logger;
    private final boolean driverAvailable;
    private HikariDataSource dataSource;

    public SqliteConnectionFactory(PersistenceConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.driverAvailable = detectDriver();
        if (driverAvailable) {
            DebugLogger.info("SqliteConnectionFactory: SQLite JDBC driver found.");
            initializePool();
        } else {
            DebugLogger.severe("SqliteConnectionFactory: SQLite JDBC driver NOT found. Persistence will be DISABLED.");
        }
    }

    private void initializePool() {
        File file = config.databaseFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            DebugLogger.warning("Failed to create persistence folder: " + parent.getAbsolutePath());
            return;
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        hc.setPoolName("MinePlus-SQLite-Pool");
        hc.setMaximumPoolSize(1); // SQLite is thread-safe for reads, but writes require strict sequential access
        hc.setConnectionTimeout(config.busyTimeoutMs());
        // No idleTimeout: the pool is fixed-size (max = 1), where idleTimeout
        // has no effect and only produces a startup warning.

        hc.addDataSourceProperty("journal_mode", "WAL");
        hc.addDataSourceProperty("synchronous", "NORMAL");
        hc.addDataSourceProperty("foreign_keys", "ON");
        hc.addDataSourceProperty("busy_timeout", String.valueOf(config.busyTimeoutMs()));

        this.dataSource = new HikariDataSource(hc);
        DebugLogger.info("SqliteConnectionFactory: HikariCP Connection Pool established.");
    }

    public boolean driverAvailable() {
        return driverAvailable;
    }

    public Connection open() {
        if (!driverAvailable || dataSource == null) {
            DebugLogger.warning("open(): Driver or DataSource not available.");
            return null;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException exception) {
            DebugLogger.severe("Failed to open sqlite connection from HikariCP pool: " + exception.getMessage(), exception);
            return null;
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private static boolean detectDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException exception) {
            DebugLogger.warning("SQLite JDBC driver not found. Persistence is disabled.");
            return false;
        }
    }
}
