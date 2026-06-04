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
    }

    public boolean driverAvailable() {
        return driverAvailable;
    }

    public Connection open() {
        if (!driverAvailable) {
            return null;
        }
        try {
            File file = config.databaseFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Failed to create persistence folder: " + parent.getAbsolutePath());
                return null;
            }

            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = " + config.busyTimeoutMs());
            }
            return connection;
        } catch (SQLException exception) {
            logger.warning("Failed to open sqlite connection: " + exception.getMessage());
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
