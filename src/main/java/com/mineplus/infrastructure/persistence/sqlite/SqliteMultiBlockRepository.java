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

public final class SqliteMultiBlockRepository implements MultiBlockRepository {

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
        return snapshots;
    }

    @Override
    public void replaceAll(Collection<MultiBlockSnapshot> snapshots) throws SQLException {
        String deleteSql = "DELETE FROM " + table;
        try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
            delete.executeUpdate();
        }

        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        String insertSql = "INSERT INTO " + table + "(id, payload) VALUES(?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
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
    }
}
