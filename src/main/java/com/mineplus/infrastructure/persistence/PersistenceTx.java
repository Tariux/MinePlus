package com.mineplus.infrastructure.persistence;

import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;

public interface PersistenceTx extends AutoCloseable {

    MultiBlockRepository multiBlocks();

    MetaRepository meta();

    void commit();

    void rollback();

    boolean active();

    @Override
    void close();
}
