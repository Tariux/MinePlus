package com.mineplus.infrastructure.persistence.sqlite;

import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class SqliteMetaRepository implements MetaRepository {

    private final Connection connection;
    private final String table;

    public SqliteMetaRepository(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    @Override
    public Optional<String> get(String key) throws SQLException {
        String sql = "SELECT value FROM " + table + " WHERE key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(result.getString("value"));
            }
        }
    }

    @Override
    public void put(String key, String value) throws SQLException {
        String sql = "INSERT INTO " + table + "(key, value) VALUES(?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        }
    }
}
