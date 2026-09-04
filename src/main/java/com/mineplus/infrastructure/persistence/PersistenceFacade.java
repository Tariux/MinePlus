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
import com.mineplus.util.DebugLogger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Write-behind persistence facade over SQLite.
 *
 * <p>Producers on the main thread call {@link #enqueueFullReplace(Collection)} which only
 * stages an immutable snapshot list in memory (cheap). Hot per-instance paths use
 * {@link #enqueueChange(MultiBlockSnapshot)} / {@link #enqueueDelete(UUID)} instead so a
 * single mutation no longer rewrites every row (incremental persistence); the two modes
 * coexist and a pending full replace supersedes staged increments. The actual SQLite
 * transaction is performed by a repeating asynchronous flush task (see
 * {@link #startAutoFlush(Plugin)}), so database I/O never blocks the main server thread.
 * A synchronous write is still available through {@link #flushNow()} for shutdown and
 * migration paths.
 *
 * <p>Concurrency: the staged payload is guarded by {@code lock}, and the flush body is
 * serialized by {@code flushLock} so an in-flight asynchronous flush and a shutdown
 * flush on the main thread cannot interleave transactions. Errors are always reported
 * to the plugin logger (independent of the debug-logging toggle) because a failed
 * write silently risks data loss.
 */
public final class PersistenceFacade {

    private final PersistenceConfig config;
    private final Logger logger;
    private final Gson gson;
    private final Object lock;
    /** Serializes flush transactions across the async flusher and the main thread. */
    private final Object flushLock;
    private final AtomicLong flushCount;
    private final AtomicLong loadedRows;
    private final AtomicLong writtenRows;
    private volatile boolean initialized;
    private volatile boolean enabled;
    private volatile long lastFlushDurationMs;
    private volatile long lastErrorAt;
    private volatile String lastError;
    private volatile List<MultiBlockSnapshot> queuedFullReplace;
    private volatile java.util.Map<UUID, MultiBlockSnapshot> queuedUpserts;
    private volatile java.util.Set<UUID> queuedDeletes;
    private volatile BukkitTask autoFlushTask;
    private SqlitePersistenceTx currentTx;
    private SqliteConnectionFactory connectionFactory;

    public PersistenceFacade(PersistenceConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.gson = new GsonBuilder().create();
        this.lock = new Object();
        this.flushLock = new Object();
        this.flushCount = new AtomicLong();
        this.loadedRows = new AtomicLong();
        this.writtenRows = new AtomicLong();
        this.initialized = false;
        this.enabled = false;
        this.lastFlushDurationMs = 0L;
        this.lastErrorAt = 0L;
        this.lastError = "";
        this.queuedFullReplace = null;
        this.queuedUpserts = null;
        this.queuedDeletes = null;
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

        DebugLogger.info("PersistenceFacade: SQLite driver detected. Database file: " + config.databaseFile());

        try (Connection connection = connectionFactory.open()) {
            if (connection == null) {
                enabled = false;
                rememberError("connection-unavailable");
                DebugLogger.severe("PersistenceFacade: Failed to open SQLite connection. Persistence is DISABLED.");
                return;
            }
            new SqliteMigrationRunner(config).migrate(connection);
            DebugLogger.info("PersistenceFacade: SQLite database migrated successfully.");
        } catch (SQLException exception) {
            enabled = false;
            rememberError("migration-failed: " + exception.getMessage());
            logger.log(Level.SEVERE, "Failed to initialize sqlite persistence: " + exception.getMessage(), exception);
        }
    }

    public List<MultiBlockSnapshot> loadAllMultiBlocks() {
        if (!enabled) {
            DebugLogger.warning("PersistenceFacade.loadAllMultiBlocks: persistence is DISABLED, returning empty list.");
            return List.of();
        }

        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                DebugLogger.warning("PersistenceFacade.loadAllMultiBlocks: transaction init failed, returning empty list.");
                return List.of();
            }
            List<MultiBlockSnapshot> snapshots = tx.multiBlocks().loadAll();
            tx.commit();
            loadedRows.addAndGet(snapshots.size());
            DebugLogger.info("PersistenceFacade: Loaded " + snapshots.size() + " multiblock snapshots from SQLite.");
            return snapshots;
        } catch (SQLException | RuntimeException exception) {
            rememberError("load-failed: " + exception.getMessage());
            logger.log(Level.SEVERE, "Failed to load multiblocks from sqlite: " + exception.getMessage(), exception);
            return List.of();
        }
    }

    /**
     * Stages a full-replace payload for the next flush. This method performs no I/O;
     * it only copies the snapshot list and marks the queue dirty, so it is safe to
     * call from hot main-thread paths (interact/place/remove/upgrade/tick).
     *
     * <p>Repeated calls overwrite any previously staged payload; the latest staged
     * state always wins, which is correct for a full-replace strategy.
     *
     * @param snapshots the complete set of live multiblock snapshots to persist
     */
    public void enqueueFullReplace(Collection<MultiBlockSnapshot> snapshots) {
        List<MultiBlockSnapshot> copy = snapshots == null ? List.of() : List.copyOf(snapshots);
        synchronized (lock) {
            queuedFullReplace = copy;
        }
        DebugLogger.info("PersistenceFacade: Enqueued " + copy.size() + " multiblock snapshots for persistence.");
    }

    /**
     * Stages a single-instance upsert for the next flush (incremental
     * persistence). No I/O; safe on hot main-thread paths. Coalesces by
     * instance id — only the latest snapshot per id is written.
     *
     * @param snapshot the changed instance snapshot to persist
     */
    public void enqueueChange(MultiBlockSnapshot snapshot) {
        if (snapshot == null || snapshot.id() == null) {
            return;
        }
        synchronized (lock) {
            if (queuedUpserts == null) {
                queuedUpserts = new java.util.LinkedHashMap<>();
            }
            queuedUpserts.put(snapshot.id(), snapshot);
            if (queuedDeletes != null) {
                queuedDeletes.remove(snapshot.id());
            }
        }
    }

    /**
     * Stages a single-instance delete for the next flush. No I/O; safe on hot
     * main-thread paths.
     *
     * @param id the removed instance id
     */
    public void enqueueDelete(UUID id) {
        if (id == null) {
            return;
        }
        synchronized (lock) {
            if (queuedDeletes == null) {
                queuedDeletes = new java.util.LinkedHashSet<>();
            }
            queuedDeletes.add(id);
            if (queuedUpserts != null) {
                queuedUpserts.remove(id);
            }
        }
    }

    /**
     * Starts the asynchronous write-behind flush cycle. The repeating task runs off
     * the main thread every {@code flushIntervalTicks} (from {@link PersistenceConfig})
     * and writes the staged payload, if any, in a single SQLite transaction. Does
     * nothing if persistence is disabled or the cycle is already running.
     *
     * <p>Must be called after {@link #initialize()} and after any synchronous
     * startup/migration flush, so that no async write overlaps the initial load.
     *
     * @param plugin the plugin instance owning the scheduler task
     */
    public void startAutoFlush(Plugin plugin) {
        if (autoFlushTask != null || !enabled) {
            return;
        }
        long intervalTicks = config.flushIntervalTicks();
        autoFlushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushNow,
                intervalTicks,
                intervalTicks
        );
        DebugLogger.info("PersistenceFacade: Async write-behind flush scheduled every " + intervalTicks + " ticks.");
    }

    /**
     * Stops the asynchronous flush cycle if running. A flush that is already executing
     * is allowed to finish; subsequent writes require an explicit {@link #flushNow()}.
     */
    public void stopAutoFlush() {
        BukkitTask task = autoFlushTask;
        autoFlushTask = null;
        if (task != null) {
            task.cancel();
            DebugLogger.info("PersistenceFacade: Async write-behind flush stopped.");
        }
    }

    /**
     * Writes the staged payload to SQLite synchronously on the calling thread.
     * Used by the async flush task, and directly by shutdown/migration paths that
     * require the write to be durable before proceeding. A no-op when nothing
     * is staged or persistence is disabled.
     */
    public void flushNow() {
        if (!enabled) {
            DebugLogger.warning("PersistenceFacade.flushNow: persistence is DISABLED, skipping flush.");
            return;
        }
        synchronized (flushLock) {
            List<MultiBlockSnapshot> fullPayload = null;
            List<MultiBlockSnapshot> upserts = null;
            List<UUID> deletes = null;
            synchronized (lock) {
                if (queuedFullReplace != null) {
                    fullPayload = queuedFullReplace;
                    queuedFullReplace = null;
                    queuedUpserts = null;
                    queuedDeletes = null;
                } else {
                    if (queuedUpserts != null && !queuedUpserts.isEmpty()) {
                        upserts = new ArrayList<>(queuedUpserts.values());
                    }
                    if (queuedDeletes != null && !queuedDeletes.isEmpty()) {
                        deletes = new ArrayList<>(queuedDeletes);
                    }
                    queuedUpserts = null;
                    queuedDeletes = null;
                }
            }

            if (fullPayload == null && upserts == null && deletes == null) {
                DebugLogger.info("PersistenceFacade.flushNow: no payload queued, skipping flush.");
                return;
            }

            long started = System.currentTimeMillis();
            if (fullPayload != null) {
                flushFullReplace(fullPayload, started);
            } else {
                flushIncremental(upserts, deletes, started);
            }
        }
    }

    private void flushFullReplace(List<MultiBlockSnapshot> payload, long started) {
        DebugLogger.info("PersistenceFacade.flushNow: Flushing " + payload.size() + " snapshots to SQLite (full replace)...");
        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                logger.log(Level.SEVERE, "PersistenceFacade.flushNow: transaction init failed, could not flush "
                        + payload.size() + " snapshots; data remains in memory until the next flush.");
                requeuePayload(payload);
                return;
            }
            tx.multiBlocks().replaceAll(payload);
            tx.meta().put("last_full_replace_at", Long.toString(System.currentTimeMillis()));
            tx.commit();
            flushCount.incrementAndGet();
            writtenRows.addAndGet(payload.size());
            lastFlushDurationMs = Math.max(0L, System.currentTimeMillis() - started);
            DebugLogger.info("PersistenceFacade.flushNow: Flushed " + payload.size() + " snapshots in "
                    + lastFlushDurationMs + "ms.");
        } catch (SQLException | RuntimeException exception) {
            rememberError("flush-failed: " + exception.getMessage());
            logger.log(Level.SEVERE, "Failed to flush persistence snapshot (" + payload.size()
                    + " snapshots); the data stays staged in memory and the next flush will retry as a full replace.",
                    exception);
            requeuePayload(payload);
        }
    }

    private void flushIncremental(List<MultiBlockSnapshot> upserts, List<UUID> deletes, long started) {
        int total = (upserts == null ? 0 : upserts.size()) + (deletes == null ? 0 : deletes.size());
        DebugLogger.info("PersistenceFacade.flushNow: Flushing " + total + " incremental changes to SQLite...");
        try (SqlitePersistenceTx tx = beginTx()) {
            if (tx == null) {
                logger.log(Level.SEVERE, "PersistenceFacade.flushNow: transaction init failed, could not flush "
                        + total + " incremental changes; they remain staged until the next flush.");
                requeueIncremental(upserts, deletes);
                return;
            }
            if (upserts != null && !upserts.isEmpty()) {
                tx.multiBlocks().upsertAll(upserts);
            }
            if (deletes != null && !deletes.isEmpty()) {
                tx.multiBlocks().deleteAll(deletes);
            }
            tx.commit();
            flushCount.incrementAndGet();
            if (upserts != null) {
                writtenRows.addAndGet(upserts.size());
            }
            lastFlushDurationMs = Math.max(0L, System.currentTimeMillis() - started);
            DebugLogger.info("PersistenceFacade.flushNow: Flushed " + total + " incremental changes in "
                    + lastFlushDurationMs + "ms.");
        } catch (SQLException | RuntimeException exception) {
            rememberError("flush-failed: " + exception.getMessage());
            logger.log(Level.SEVERE, "Failed to flush incremental persistence changes (" + total
                    + "); the changes stay staged in memory and the next flush will retry.",
                    exception);
            requeueIncremental(upserts, deletes);
        }
    }

    private void requeueIncremental(List<MultiBlockSnapshot> upserts, List<UUID> deletes) {
        synchronized (lock) {
            if (upserts != null) {
                if (queuedUpserts == null) {
                    queuedUpserts = new java.util.LinkedHashMap<>();
                }
                for (MultiBlockSnapshot snapshot : upserts) {
                    queuedUpserts.putIfAbsent(snapshot.id(), snapshot);
                }
            }
            if (deletes != null) {
                if (queuedDeletes == null) {
                    queuedDeletes = new java.util.LinkedHashSet<>();
                }
                queuedDeletes.addAll(deletes);
            }
        }
    }

    /**
     * Re-stages a payload whose flush failed so that the next flush cycle (or the
     * shutdown flush) retries it. Only stages the payload if no newer payload has
     * been enqueued in the meantime — full-replace semantics dictate that the
     * newest staged state always wins, so an older failed payload is discarded
     * rather than overwriting fresher data.
     *
     * @param payload the payload whose write attempt failed
     */
    private void requeuePayload(List<MultiBlockSnapshot> payload) {
        synchronized (lock) {
            if (queuedFullReplace == null) {
                queuedFullReplace = payload;
            }
        }
    }

    /**
     * Stops the async flush cycle and performs a final synchronous flush.
     *
     * @param timeoutMs accepted for API compatibility; the flush itself is bounded
     *                  by SQLite's busy timeout rather than this value
     */
    public void shutdown(long timeoutMs) {
        stopAutoFlush();
        flushNow();
        if (connectionFactory != null) {
            connectionFactory.shutdown();
        }
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
