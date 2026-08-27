package com.mineplus.infrastructure.persistence.sqlite;

import com.google.gson.Gson;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

public final class SqliteMultiBlockRepository implements MultiBlockRepository {

    private final Connection connection;
    private final String table;
    private final Gson gson;
    private final Logger logger;

    public SqliteMultiBlockRepository(Connection connection, String table, Gson gson) {
        this.connection = connection;
        this.table = table;
        this.gson = gson;
        this.logger = Logger.getLogger(SqliteMultiBlockRepository.class.getName());
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
        logger.info("SqliteMultiBlockRepository: Loaded " + snapshots.size() + " rows from '" + table + "'.");
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

        // Perform deletions
        if (!idsToDelete.isEmpty()) {
            String deleteSql = "DELETE FROM " + table + " WHERE id IN (" + String.join(",", java.util.Collections.nCopies(idsToDelete.size(), "?")) + ")";
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                for (int i = 0; i < idsToDelete.size(); i++) {
                    delete.setString(i + 1, idsToDelete.get(i));
                }
                delete.executeUpdate();
                logger.info("SqliteMultiBlockRepository: Deleted " + idsToDelete.size() + " rows from '" + table + "'.");
            }
        }

        // Perform upserts
        if (snapshots == null || snapshots.isEmpty()) {
            logger.info("SqliteMultiBlockRepository: No snapshots to upsert, skipping upsert operation for '" + table + "'.");
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
        logger.info("SqliteMultiBlockRepository: Upserted " + snapshots.size() + " rows in '" + table + "'.");
    }
}
