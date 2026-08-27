package com.mineplus.infrastructure.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.infrastructure.persistence.sqlite.SqliteConnectionFactory;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMetaRepository;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMigrationRunner;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMultiBlockRepository;
import com.mineplus.infrastructure.persistence.sqlite.SqlitePersistenceTx;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class PersistenceFacade {

    private final PersistenceConfig config;
    private final Logger logger;
    private final Gson gson;
    private final Object lock;
    private final AtomicLong flushCount;
    private final AtomicLong loadedRows;
    private final AtomicLong writtenRows;
    private volatile boolean initialized;
    private volatile boolean enabled;
    private volatile long lastFlushDurationMs;
    private volatile long lastErrorAt;
    private volatile String lastError;
    private volatile List<MultiBlockSnapshot> queuedFullReplace;
    private SqlitePersistenceTx currentTx;
    private SqliteConnectionFactory connectionFactory;

    public PersistenceFacade(PersistenceConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.gson = new GsonBuilder().create();
        this.lock = new Object();
        this.flushCount = new AtomicLong();
        this.loadedRows = new AtomicLong();
        this.writtenRows = new AtomicLong();
        this.initialized = false;
        this.enabled = false;
        this.lastFlushDurationMs = 0L;
        this.lastErrorAt = 0L;
        this.lastError = "";
        this.queuedFullReplace = null;
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        connectionFactory = new SqliteConnectionFactory(config, logger);
        enabled = connectionFactory.driverAvailable();
        if (!enabled) {
            lastError = "driver-unavailable";
            logger.warning("PersistenceFacade initialized but SQLite driver is unavailable. Persistence is DISABLED.");
            return;
        }

        logger.info("PersistenceFacade: SQLite driver detected. Database file: " + config.databaseFile());

        try (Connection connection = connectionFactory.open()) {
            if (connection == null) {
                enabled = false;
                rememberError("connection-unavailable");
                logger.severe("PersistenceFacade: Failed to open SQLite connection. Persistence is DISABLED.");
                return;
            }
            new SqliteMigrationRunner(config).migrate(connection);
            logger.info("PersistenceFacade: SQLite database migrated successfully.");
        } catch (SQLException exception) {
            enabled = false;
            rememberError("migration-failed: " + exception.getMessage());
            logger.severe("Failed to initialize sqlite persistence: " + exception.getMessage());
        }
    }

    public List<MultiBlockSnapshot> loadAllMultiBlocks() {
        if (!enabled) {
            logger.warning("PersistenceFacade.loadAllMultiBlocks: persistence is DISABLED, returning empty list.");
            return List.of();
        }

        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                logger.warning("PersistenceFacade.loadAllMultiBlocks: transaction init failed, returning empty list.");
                return List.of();
            }
            List<MultiBlockSnapshot> snapshots = tx.multiBlocks().loadAll();
            tx.commit();
            loadedRows.addAndGet(snapshots.size());
            logger.info("PersistenceFacade: Loaded " + snapshots.size() + " multiblock snapshots from SQLite.");
            return snapshots;
        } catch (SQLException | RuntimeException exception) {
            rememberError("load-failed: " + exception.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Failed to load multiblocks from sqlite: " + exception.getMessage(), exception);
            return List.of();
        }
    }

    public void enqueueFullReplace(Collection<MultiBlockSnapshot> snapshots) {
        List<MultiBlockSnapshot> copy = snapshots == null ? List.of() : List.copyOf(snapshots);
        synchronized (lock) {
            queuedFullReplace = copy;
        }
        logger.info("PersistenceFacade: Enqueued " + copy.size() + " multiblock snapshots for persistence.");
    }

    public void flushNow() {
        if (!enabled) {
            logger.warning("PersistenceFacade.flushNow: persistence is DISABLED, skipping flush.");
            return;
        }
        List<MultiBlockSnapshot> payload;
        synchronized (lock) {
            payload = queuedFullReplace;
            queuedFullReplace = null;
        }

        if (payload == null) {
            logger.info("PersistenceFacade.flushNow: no payload queued, skipping flush.");
            return;
        }

        long started = System.currentTimeMillis();
        logger.info("PersistenceFacade.flushNow: Flushing " + payload.size() + " snapshots to SQLite...");
        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                logger.severe("PersistenceFacade.flushNow: transaction init failed, could not flush.");
                return;
            }
            tx.multiBlocks().replaceAll(payload);
            tx.meta().put("last_full_replace_at", Long.toString(System.currentTimeMillis()));
            tx.commit();
            flushCount.incrementAndGet();
            writtenRows.addAndGet(payload.size());
            lastFlushDurationMs = Math.max(0L, System.currentTimeMillis() - started);
            logger.info("PersistenceFacade.flushNow: Flushed " + payload.size() + " snapshots in " + lastFlushDurationMs + "ms.");
        } catch (SQLException | RuntimeException exception) {
            rememberError("flush-failed: " + exception.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Failed to flush persistence snapshot: " + exception.getMessage(), exception);
        }
    }

    public void shutdown(long timeoutMs) {
        flushNow();
    }

    public PersistenceTx beginTransaction() {
        if (currentTx != null) {
            return currentTx;
        }
        SqlitePersistenceTx tx = beginTx();
        currentTx = tx;
        return tx;
    }

    public void commitTransaction() {
        if (currentTx == null) {
            return;
        }
        try {
            currentTx.commit();
        } finally {
            currentTx.close();
            currentTx = null;
        }
    }

    public void rollbackTransaction() {
        if (currentTx == null) {
            return;
        }
        try {
            currentTx.rollback();
        } finally {
            currentTx.close();
            currentTx = null;
        }
    }

    public PersistenceStats stats() {
        return new PersistenceStats(
                enabled,
                flushCount.get(),
                loadedRows.get(),
                writtenRows.get(),
                lastFlushDurationMs,
                lastErrorAt,
                lastError == null ? "" : lastError
        );
    }

    private SqlitePersistenceTx beginTx() {
        if (connectionFactory == null) {
            return null;
        }
        Connection connection = connectionFactory.open();
        if (connection == null) {
            return null;
        }
        try {
            connection.setAutoCommit(false);
            String prefix = config.tablePrefix();
            MultiBlockRepository multiBlocks = new SqliteMultiBlockRepository(connection, prefix + "multiblocks", gson);
            MetaRepository meta = new SqliteMetaRepository(connection, prefix + "meta");
            return new SqlitePersistenceTx(connection, logger, multiBlocks, meta);
        } catch (SQLException exception) {
            rememberError("tx-init-failed: " + exception.getMessage());
            logger.warning("Failed to initialize sqlite transaction: " + exception.getMessage());
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            return null;
        }
    }

    private void rememberError(String message) {
        lastErrorAt = System.currentTimeMillis();
        lastError = message;
    }
}
