package com.mineplus.infrastructure.persistence.repository;

import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

public interface MultiBlockRepository {

    List<MultiBlockSnapshot> loadAll() throws SQLException;

    void replaceAll(Collection<MultiBlockSnapshot> snapshots) throws SQLException;
}
