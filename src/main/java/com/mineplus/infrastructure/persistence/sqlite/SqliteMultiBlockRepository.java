package com.mineplus.infrastructure.persistence.sqlite;

import com.google.gson.Gson;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import com.mineplus.util.DebugLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SqliteMultiBlockRepository implements MultiBlockRepository {

    /**
     * Maximum host parameters per DELETE statement. SQLite's default
     * SQLITE_MAX_VARIABLE_NUMBER is 999; exceeding it makes the statement fail,
     * so large removal sets must be deleted in batches.
     */
    private static final int DELETE_BATCH_SIZE = 500;

    private final Connection connection;
    private final String table;
    private final Gson gson;

    public SqliteMultiBlockRepository(Connection connection, String table, Gson gson) {
        this.connection = connection;
        this.table = table;
        this.gson = gson;
    }

    @Override
    public List<MultiBlockSnapshot> loadAll() throws SQLException {
        String sql = "SELECT payload FROM " + table + " ORDER BY id";
        List<MultiBlockSnapshot> snapshots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                MultiBlockSnapshot snapshot = gson.fromJson(result.getString("payload"), MultiBlockSnapshot.class);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        }
        DebugLogger.info("SqliteMultiBlockRepository: Loaded " + snapshots.size() + " rows from '" + table + "'.");
        return snapshots;
    }

    @Override
    public void replaceAll(Collection<MultiBlockSnapshot> snapshots) throws SQLException {
        // Get current IDs in the database
        List<String> currentDbIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + table);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                currentDbIds.add(result.getString("id"));
            }
        }

        // Identify IDs to keep (from incoming snapshots)
        java.util.Set<String> newSnapshotIds = new java.util.HashSet<>();
        if (snapshots != null) {
            for (MultiBlockSnapshot snapshot : snapshots) {
                if (snapshot != null && snapshot.id() != null) {
                    newSnapshotIds.add(snapshot.id().toString());
                }
            }
        }

        // Identify IDs to delete (in DB but not in new snapshots)
        List<String> idsToDelete = new ArrayList<>();
        for (String dbId : currentDbIds) {
            if (!newSnapshotIds.contains(dbId)) {
                idsToDelete.add(dbId);
            }
        }

        // Perform deletions in batches to stay under SQLite's host-parameter limit
        if (!idsToDelete.isEmpty()) {
            int totalDeleted = 0;
            for (int from = 0; from < idsToDelete.size(); from += DELETE_BATCH_SIZE) {
                int to = Math.min(from + DELETE_BATCH_SIZE, idsToDelete.size());
                List<String> batch = idsToDelete.subList(from, to);
                String deleteSql = "DELETE FROM " + table
                        + " WHERE id IN (" + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ")";
                try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                    for (int i = 0; i < batch.size(); i++) {
                        delete.setString(i + 1, batch.get(i));
                    }
                    totalDeleted += delete.executeUpdate();
                }
            }
            DebugLogger.info("SqliteMultiBlockRepository: Deleted " + totalDeleted
                    + " rows from '" + table + "' in " + ((idsToDelete.size() + DELETE_BATCH_SIZE - 1) / DELETE_BATCH_SIZE) + " batch(es).");
        }

        // Perform upserts
        if (snapshots == null || snapshots.isEmpty()) {
            DebugLogger.info("SqliteMultiBlockRepository: No snapshots to upsert, skipping upsert operation for '" + table + "'.");
            return;
        }

        String upsertSql = "INSERT OR REPLACE INTO " + table + "(id, payload) VALUES(?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(upsertSql)) {
            for (MultiBlockSnapshot snapshot : snapshots) {
                if (snapshot == null || snapshot.id() == null) {
                    continue;
                }
                insert.setString(1, snapshot.id().toString());
                insert.setString(2, gson.toJson(snapshot));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        DebugLogger.info("SqliteMultiBlockRepository: Upserted " + snapshots.size() + " rows in '" + table + "'.");
    }
}
