package com.mineplus.infrastructure.persistence.repository;

import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MultiBlockRepository {

    List<MultiBlockSnapshot> loadAll() throws SQLException;

    void replaceAll(Collection<MultiBlockSnapshot> snapshots) throws SQLException;

    /** Upserts the given snapshots by id without touching any other row. */
    void upsertAll(Collection<MultiBlockSnapshot> snapshots) throws SQLException;

    /** Deletes the rows for the given ids without touching any other row. */
    void deleteAll(Collection<UUID> ids) throws SQLException;
}
