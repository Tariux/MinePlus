package com.mineplus.infrastructure.persistence;

import com.mineplus.infrastructure.persistence.repository.MetaRepository;
import com.mineplus.infrastructure.persistence.repository.MultiBlockRepository;
import com.mineplus.infrastructure.persistence.repository.VirtualBlockRepository;

public interface PersistenceTx extends AutoCloseable {

    MultiBlockRepository multiBlocks();

    VirtualBlockRepository virtualBlocks();

    MetaRepository meta();

    void commit();

    void rollback();

    boolean active();

    @Override
    void close();
}
