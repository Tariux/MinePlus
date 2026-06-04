package com.mineplus.infrastructure.persistence.repository;

import java.sql.SQLException;
import java.util.Optional;

public interface MetaRepository {

    Optional<String> get(String key) throws SQLException;

    void put(String key, String value) throws SQLException;
}
