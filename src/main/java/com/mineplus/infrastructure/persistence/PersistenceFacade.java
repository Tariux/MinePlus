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
            return;
        }

        try (Connection connection = connectionFactory.open()) {
            if (connection == null) {
                enabled = false;
                rememberError("connection-unavailable");
                return;
            }
            new SqliteMigrationRunner(config).migrate(connection);
        } catch (SQLException exception) {
            enabled = false;
            rememberError("migration-failed: " + exception.getMessage());
            logger.warning("Failed to initialize sqlite persistence: " + exception.getMessage());
        }
    }

    public List<MultiBlockSnapshot> loadAllMultiBlocks() {
        if (!enabled) {
            return List.of();
        }

        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                return List.of();
            }
            List<MultiBlockSnapshot> snapshots = tx.multiBlocks().loadAll();
            tx.commit();
            loadedRows.addAndGet(snapshots.size());
            return snapshots;
        } catch (SQLException | RuntimeException exception) {
            rememberError("load-failed: " + exception.getMessage());
            logger.warning("Failed to load multiblocks from sqlite: " + exception.getMessage());
            return List.of();
        }
    }

    public void enqueueFullReplace(Collection<MultiBlockSnapshot> snapshots) {
        List<MultiBlockSnapshot> copy = snapshots == null ? List.of() : List.copyOf(snapshots);
        synchronized (lock) {
            queuedFullReplace = copy;
        }
    }

    public void flushNow() {
        if (!enabled) {
            return;
        }
        List<MultiBlockSnapshot> payload;
        synchronized (lock) {
            payload = queuedFullReplace;
            queuedFullReplace = null;
        }

        if (payload == null) {
            return;
        }

        long started = System.currentTimeMillis();
        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                return;
            }
            tx.multiBlocks().replaceAll(payload);
            tx.meta().put("last_full_replace_at", Long.toString(System.currentTimeMillis()));
            tx.commit();
            flushCount.incrementAndGet();
            writtenRows.addAndGet(payload.size());
            lastFlushDurationMs = Math.max(0L, System.currentTimeMillis() - started);
        } catch (SQLException | RuntimeException exception) {
            rememberError("flush-failed: " + exception.getMessage());
            logger.warning("Failed to flush persistence snapshot: " + exception.getMessage());
        }
    }

    public void shutdown(long timeoutMs) {
        flushNow();
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
