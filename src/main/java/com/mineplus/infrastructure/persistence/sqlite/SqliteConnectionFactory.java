package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.PersistenceConfig;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public final class SqliteConnectionFactory {

    private final PersistenceConfig config;
    private final Logger logger;
    private final boolean driverAvailable;

    public SqliteConnectionFactory(PersistenceConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.driverAvailable = detectDriver(logger);
        if (driverAvailable) {
            logger.info("SqliteConnectionFactory: SQLite JDBC driver found.");
        } else {
            logger.severe("SqliteConnectionFactory: SQLite JDBC driver NOT found. Persistence will be DISABLED.");
        }
    }

    public boolean driverAvailable() {
        return driverAvailable;
    }

    public Connection open() {
        if (!driverAvailable) {
            logger.warning("open(): Driver not available.");
            return null;
        }
        try {
            File file = config.databaseFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Failed to create persistence folder: " + parent.getAbsolutePath());
                return null;
            }

            logger.info("open(): Opening SQLite connection to " + file.getAbsolutePath());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = " + config.busyTimeoutMs());
            }
            logger.info("open(): SQLite connection established.");
            return connection;
        } catch (SQLException exception) {
            logger.log(java.util.logging.Level.SEVERE, "Failed to open sqlite connection: " + exception.getMessage(), exception);
            return null;
        }
    }

    private static boolean detectDriver(Logger logger) {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException exception) {
            logger.warning("SQLite JDBC driver not found. Persistence is disabled.");
            return false;
        }
    }
}
