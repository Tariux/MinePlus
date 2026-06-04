package com.mineplus.infrastructure.persistence;

import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import java.util.Collection;
import java.util.List;

public record PersistenceSnapshot(List<MultiBlockSnapshot> multiBlocks) {

    public PersistenceSnapshot {
        multiBlocks = multiBlocks == null ? List.of() : List.copyOf(multiBlocks);
    }

    public static PersistenceSnapshot of(Collection<MultiBlockSnapshot> snapshots) {
        return new PersistenceSnapshot(snapshots == null ? List.of() : List.copyOf(snapshots));
    }
}
