package com.mineplus.infrastructure.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import com.mineplus.infrastructure.persistence.repository.VirtualBlockRepository;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.infrastructure.persistence.snapshot.VirtualBlockSnapshot;
import com.mineplus.infrastructure.persistence.sqlite.SqliteConnectionFactory;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMetaRepository;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMigrationRunner;
import com.mineplus.infrastructure.persistence.sqlite.SqliteMultiBlockRepository;
import com.mineplus.infrastructure.persistence.sqlite.SqlitePersistenceTx;
import com.mineplus.infrastructure.persistence.sqlite.SqliteVirtualBlockRepository;
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
    private volatile List<MultiBlockSnapshot> queuedMultiBlockReplace;
    private volatile List<VirtualBlockSnapshot> queuedVirtualBlockReplace;
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
        this.queuedMultiBlockReplace = null;
        this.queuedVirtualBlockReplace = null;
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

    public List<VirtualBlockSnapshot> loadAllVirtualBlocks() {
        if (!enabled) {
            return List.of();
        }

        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                return List.of();
            }
            List<VirtualBlockSnapshot> snapshots = tx.virtualBlocks().loadAll();
            tx.commit();
            loadedRows.addAndGet(snapshots.size());
            return snapshots;
        } catch (SQLException | RuntimeException exception) {
            rememberError("load-vblocks-failed: " + exception.getMessage());
            logger.warning("Failed to load virtualblocks from sqlite: " + exception.getMessage());
            return List.of();
        }
    }

    public void enqueueMultiBlockReplace(Collection<MultiBlockSnapshot> snapshots) {
        List<MultiBlockSnapshot> copy = snapshots == null ? List.of() : List.copyOf(snapshots);
        synchronized (lock) {
            queuedMultiBlockReplace = copy;
        }
    }

    public void enqueueVirtualBlockReplace(Collection<VirtualBlockSnapshot> snapshots) {
        List<VirtualBlockSnapshot> copy = snapshots == null ? List.of() : List.copyOf(snapshots);
        synchronized (lock) {
            queuedVirtualBlockReplace = copy;
        }
    }

    @Deprecated
    public void enqueueFullReplace(Collection<MultiBlockSnapshot> snapshots) {
        enqueueMultiBlockReplace(snapshots);
    }

    public void flushNow() {
        if (!enabled) {
            return;
        }
        List<MultiBlockSnapshot> mbPayload;
        List<VirtualBlockSnapshot> vbPayload;
        synchronized (lock) {
            mbPayload = queuedMultiBlockReplace;
            vbPayload = queuedVirtualBlockReplace;
            queuedMultiBlockReplace = null;
            queuedVirtualBlockReplace = null;
        }

        if (mbPayload == null && vbPayload == null) {
            return;
        }

        long started = System.currentTimeMillis();
        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                return;
            }
            if (mbPayload != null) {
                tx.multiBlocks().replaceAll(mbPayload);
                writtenRows.addAndGet(mbPayload.size());
            }
            if (vbPayload != null) {
                tx.virtualBlocks().replaceAll(vbPayload);
                writtenRows.addAndGet(vbPayload.size());
            }
            tx.meta().put("last_flush_at", Long.toString(System.currentTimeMillis()));
            tx.commit();
            flushCount.incrementAndGet();
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
            VirtualBlockRepository virtualBlocks = new SqliteVirtualBlockRepository(connection, prefix + "virtualblocks", gson);
            MetaRepository meta = new SqliteMetaRepository(connection, prefix + "meta");
            return new SqlitePersistenceTx(connection, logger, multiBlocks, virtualBlocks, meta);
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
