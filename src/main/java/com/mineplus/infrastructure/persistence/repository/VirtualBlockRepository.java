package com.mineplus.infrastructure.persistence.repository;

import com.mineplus.infrastructure.persistence.snapshot.VirtualBlockSnapshot;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

public interface VirtualBlockRepository {
    List<VirtualBlockSnapshot> loadAll() throws SQLException;
    void replaceAll(Collection<VirtualBlockSnapshot> snapshots) throws SQLException;
}
